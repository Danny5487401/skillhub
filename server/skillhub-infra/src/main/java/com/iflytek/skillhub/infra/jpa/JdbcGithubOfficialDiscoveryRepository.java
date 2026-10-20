package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryCatalog;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The configuration lock serializes catalog reconciliation, scheduling and administrator changes. */
@Repository
public class JdbcGithubOfficialDiscoveryRepository implements GithubOfficialDiscoveryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GithubOfficialDiscoveryCatalog catalog;
    private final JdbcGithubOfficialShardRepository shards;
    private final JdbcGithubOfficialOwnerRepository owners;

    public JdbcGithubOfficialDiscoveryRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                                 GithubOfficialDiscoveryCatalog catalog, JdbcGithubOfficialShardRepository shards, JdbcGithubOfficialOwnerRepository owners) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.catalog = catalog;
        this.shards = shards;
        this.owners = owners;
    }

    @Override
    @Transactional
    public int enqueueDue(Instant now, int maxQueries) {
        if (maxQueries < 1 || maxQueries > 100) {
            throw new IllegalArgumentException("Official discovery scheduler batch must contain 1 to 100 queries");
        }
        Configuration configuration = jdbc.query("""
                SELECT namespace_id, enabled, config_version, budget_profile, strategy_generation, strategy_version
                FROM github_deep_discovery_config WHERE id = TRUE FOR UPDATE
                """, result -> {
            if (!result.next()) {
                throw new IllegalStateException("GitHub deep discovery configuration is missing");
            }
            return new Configuration(result.getLong("namespace_id"), result.getBoolean("enabled"),
                    result.getLong("config_version"), result.getString("budget_profile"),
                    result.getLong("strategy_generation"), result.getString("strategy_version"));
        });
        if (configuration.strategyGeneration() > GithubOfficialDiscoveryCatalog.GENERATION) {
            return 0;
        }
        if (configuration.strategyGeneration() == GithubOfficialDiscoveryCatalog.GENERATION
                && !GithubOfficialDiscoveryCatalog.VERSION.equals(configuration.strategyVersion())) {
            throw new IllegalStateException("Official strategy changes require a new catalog generation");
        }
        reconcile(configuration.namespaceId(), now);
        if (!configuration.enabled()) {
            return 0;
        }
        owners.reconcile(configuration.namespaceId(), now);
        Integer available = jdbc.query("""
                SELECT least(candidate_per_lease_limit, candidate_pending_limit - (
                    SELECT count(*) FROM github_discovery_candidate
                    WHERE namespace_id = ? AND status IN ('PENDING', 'VALIDATING'))) AS budget
                FROM github_sync_admission_policy WHERE id = TRUE
                """, result -> result.next() ? result.getInt("budget") : null, configuration.namespaceId());
        if (available == null || available < 1) {
            return 0;
        }
        shards.replayFailed(configuration.namespaceId());
        // A terminal acquisition failure is a replay boundary, not successful coverage.
        jdbc.update("""
                UPDATE github_deep_discovery_query query SET active_task_id = NULL, checkpoint = NULL,
                    next_due_at = GREATEST(query.next_due_at, task.updated_at + INTERVAL '1 hour'),
                    wait_reason = 'FAILED_REPLAY'
                FROM github_sync_task task WHERE query.active_task_id = task.id AND task.status = 'FAILED'
                    AND query.namespace_id = ?
                """, configuration.namespaceId());
        List<QueryProgress> due = jdbc.query("""
                SELECT id, definition_json::text, covered_until, github_owner_id, owner_login, seed_repository_id
                FROM github_deep_discovery_query
                WHERE namespace_id = ? AND (retired_at IS NULL OR EXISTS (
                    SELECT 1 FROM github_official_discovery_shard shard
                    WHERE shard.query_id = github_deep_discovery_query.id AND shard.state IN ('PENDING', 'GAP')))
                    AND NOT EXISTS (SELECT 1 FROM github_official_owner_eligibility owner
                        WHERE owner.query_id = github_deep_discovery_query.id AND owner.excluded)
                    AND next_due_at <= ? AND active_task_id IS NULL
                ORDER BY next_due_at, query_key LIMIT ? FOR UPDATE SKIP LOCKED
                """, (result, row) -> new QueryProgress(result.getObject("id", UUID.class),
                definition(result.getString("definition_json")),
                result.getTimestamp("covered_until") == null ? null : result.getTimestamp("covered_until").toInstant(),
                (Long) result.getObject("github_owner_id"), result.getString("owner_login"),
                (Long) result.getObject("seed_repository_id")), configuration.namespaceId(), Timestamp.from(now), maxQueries);
        Instant cutoff = now.truncatedTo(ChronoUnit.SECONDS);
        int queued = 0;
        for (QueryProgress progress : due) {
            Instant since = progress.coveredUntil() == null || "HISTORY_RECHECK".equals(progress.definition().cadence()) ? Instant.EPOCH
                    : progress.coveredUntil().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
            if (since.isBefore(Instant.EPOCH)) {
                since = Instant.EPOCH;
            }
            if (!since.isBefore(cutoff)) {
                continue;
            }
            UUID proposedTaskId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            ObjectNode freshSnapshot = snapshot(configuration, progress, proposedTaskId, since, cutoff, available);
            boolean owner = progress.ownerId() != null;
            var work = owner ? new JdbcGithubOfficialShardRepository.Work(null, proposedTaskId, false, freshSnapshot, null, 0)
                    : shards.next(progress.id(), proposedTaskId, freshSnapshot, available, now);
            List<String> capabilities = owner ? GithubOfficialDiscoveryCatalog.OWNER_REQUIRED_CAPABILITIES
                    : GithubOfficialDiscoveryCatalog.REQUIRED_CAPABILITIES;
            UUID taskId = work.taskId();
            ObjectNode snapshot = work.snapshot();
            if (work.reuse()) {
                int locked = jdbc.queryForObject("SELECT count(*) FROM (SELECT id FROM github_sync_task WHERE id = ? AND status = 'PARTIAL' FOR UPDATE) task", Integer.class, taskId);
                if (locked != 1) {
                    throw new IllegalStateException("GAP replay requires a terminal PARTIAL task");
                }
                jdbc.update("DELETE FROM github_discovery_batch WHERE task_id = ?", taskId);
                jdbc.update("DELETE FROM github_discovery_task_hit WHERE task_id = ?", taskId);
                jdbc.update("""
                        UPDATE github_sync_task SET status = 'QUEUED', snapshot_json = ?::jsonb,
                            due_at = ?, retry_at = NULL, completed_at = NULL, lease_token_hash = NULL,
                            lease_expires_at = NULL, leased_by = NULL, official_pause_reason = NULL, updated_at = ?
                        WHERE id = ?
                        """, snapshot.toString(), Timestamp.from(now), Timestamp.from(now), taskId);
                jdbc.update("""
                        UPDATE github_sync_run SET status = 'QUEUED', finished_at = NULL
                        WHERE id = (SELECT run_id FROM github_sync_task WHERE id = ?)
                        """, taskId);
            } else {
                jdbc.update("""
                        INSERT INTO github_sync_run (id, namespace_id, kind, status, created_at)
                        VALUES (?, ?, 'DISCOVERY', 'QUEUED', ?)
                        """, runId, configuration.namespaceId(), Timestamp.from(now));
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO github_sync_task
                                (id, run_id, namespace_id, kind, status, dedupe_key, priority, snapshot_json,
                                 due_at, created_at, updated_at, required_capabilities, official_query_id)
                            VALUES (?, ?, ?, 'DISCOVERY', 'QUEUED', ?, 10, ?::jsonb, ?, ?, ?, ?, ?)
                            """);
                    statement.setObject(1, taskId);
                    statement.setObject(2, runId);
                    statement.setLong(3, configuration.namespaceId());
                    statement.setString(4, "official-discovery:" + progress.id());
                    statement.setString(5, snapshot.toString());
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.setArray(9, connection.createArrayOf("text", capabilities.toArray()));
                    statement.setObject(10, progress.id());
                    return statement;
                });
                jdbc.update("UPDATE github_sync_task SET lease_generation = ? WHERE id = ?", work.generation(), taskId);
            }
            jdbc.update("UPDATE github_sync_task SET discovery_checkpoint = ? WHERE id = ?", work.checkpoint(), taskId);
            if (!owner) {
                shards.assigned(work);
            }
            jdbc.update("""
                    UPDATE github_deep_discovery_query
                    SET active_task_id = ?, checkpoint = ?, wait_reason = 'AWAITING_RESULT' WHERE id = ?
                    """, taskId, work.checkpoint(), progress.id());
            queued++;
        }
        return queued;
    }

    private void reconcile(long namespaceId, Instant now) {
        List<UUID> current = new ArrayList<>();
        for (var definition : catalog.definitions()) {
            String json = mapper.valueToTree(definition).toString();
            jdbc.update("""
                    INSERT INTO github_deep_discovery_query
                        (id, namespace_id, query_key, query_version, strategy_version, definition_json, next_due_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (namespace_id, query_key, query_version) DO NOTHING
                    """, UUID.randomUUID(), namespaceId, definition.key(), definition.version(),
                    GithubOfficialDiscoveryCatalog.VERSION, json, Timestamp.from(now));
            UUID id = jdbc.query("""
                    SELECT id, definition_json::text FROM github_deep_discovery_query
                    WHERE namespace_id = ? AND query_key = ? AND query_version = ?
                    """, result -> {
                if (!result.next()) {
                    throw new IllegalStateException("Official query is missing");
                }
                var previous = definition(result.getString("definition_json"));
                if (!previous.channel().equals(definition.channel()) || !previous.query().equals(definition.query())) {
                    throw new IllegalStateException("Official query changes require a new query version");
                }
                return result.getObject("id", UUID.class);
            }, namespaceId, definition.key(), definition.version());
            current.add(id);
            jdbc.update("""
                    UPDATE github_deep_discovery_query
                    SET strategy_version = ?, definition_json = ?::jsonb, retired_at = NULL WHERE id = ?
                      AND (strategy_version IS DISTINCT FROM ? OR definition_json <> ?::jsonb OR retired_at IS NOT NULL)
                    """, GithubOfficialDiscoveryCatalog.VERSION, json, id, GithubOfficialDiscoveryCatalog.VERSION, json);
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE github_deep_discovery_query SET retired_at = ?
                    WHERE namespace_id = ? AND query_source = 'CATALOG' AND retired_at IS NULL AND NOT (id = ANY (?))
                    """);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setLong(2, namespaceId);
            statement.setArray(3, connection.createArrayOf("uuid", current.toArray()));
            return statement;
        });
        jdbc.update("UPDATE github_deep_discovery_config SET strategy_version = ?, strategy_generation = ? "
                + "WHERE id = TRUE AND strategy_generation < ?", GithubOfficialDiscoveryCatalog.VERSION,
                GithubOfficialDiscoveryCatalog.GENERATION, GithubOfficialDiscoveryCatalog.GENERATION);
    }

    private ObjectNode snapshot(Configuration configuration, QueryProgress progress, UUID taskId,
                                Instant since, Instant cutoff, int candidateBudget) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode official = root.putObject("officialDiscovery");
        official.put("formatVersion", 1);
        official.put("configurationVersion", configuration.version());
        official.put("strategyVersion", GithubOfficialDiscoveryCatalog.VERSION);
        official.put("queryId", progress.id().toString());
        official.put("queryVersion", progress.definition().version());
        official.put("shardId", taskId.toString());
        official.put("channel", progress.definition().channel());
        official.put("mode", since.equals(Instant.EPOCH) ? "HISTORICAL" : "INCREMENTAL");
        official.set("query", mapper.valueToTree(progress.definition().query()));
        official.putObject("range").put("since", since.toString()).put("until", cutoff.toString());
        official.put("fixedCutoffAt", cutoff.toString());
        // Search page request bounds only; not tree/owner work or calibrated profile allocations.
        official.putObject("budget").put("profile", configuration.profile())
                .put("maxRequests", 10).put("maxPages", 10).put("candidateBudget", candidateBudget)
                .put("maxSplitDepth", JdbcGithubOfficialShardRepository.MAX_DEPTH)
                .put("maxChildShards", JdbcGithubOfficialShardRepository.MAX_CHILDREN);
        if (progress.ownerId() != null) {
            official.put("mode", "OWNER_RECHECK");
            official.put("ownerId", progress.ownerId());
            official.put("ownerLogin", progress.ownerLogin());
            ((ObjectNode) official.path("query")).put("value", progress.ownerLogin());
            official.put("seedRepositoryId", progress.seedRepositoryId());
            official.put("expansionDepth", 1);
            ((ObjectNode) official.path("budget")).put("maxSplitDepth", 0).put("maxChildShards", 0);
        }
        return root;
    }

    private GithubOfficialDiscoveryCatalog.Definition definition(String json) {
        try {
            return mapper.readValue(json, GithubOfficialDiscoveryCatalog.Definition.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted official query definition", exception);
        }
    }

    private record Configuration(long namespaceId, boolean enabled, long version, String profile,
                                 long strategyGeneration, String strategyVersion) {}
    private record QueryProgress(UUID id, GithubOfficialDiscoveryCatalog.Definition definition,
                                 Instant coveredUntil, Long ownerId, String ownerLogin, Long seedRepositoryId) {}
}
