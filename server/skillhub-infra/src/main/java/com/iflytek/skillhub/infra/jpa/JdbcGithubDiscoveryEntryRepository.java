package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntry;
import com.iflytek.skillhub.githubsync.GithubDiscoveryEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Discovery configuration uses JSONB snapshots and PostgreSQL RETURNING in one statement. */
@Repository
public class JdbcGithubDiscoveryEntryRepository implements GithubDiscoveryEntryRepository {
    private static final String COLUMNS = " " + """
            id, namespace_id, entry_type, title, owner_login, repository_name, repository_id,
            source_visibility,
            criteria_json::text AS criteria_json,
            rule_version, enabled, target_interval_seconds, next_due_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGithubDiscoveryEntryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GithubDiscoveryEntry> listPlatformEntries() {
        return jdbc.query("SELECT " + COLUMNS + " FROM github_discovery_entry "
                        + "WHERE namespace_id = ? ORDER BY entry_type, title, id",
                (result, row) -> map(result), platformNamespaceId());
    }

    @Override
    public List<GithubDiscoveryEntry> listTeamRepositories(long namespaceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM github_discovery_entry "
                        + "WHERE namespace_id = ? AND entry_type = 'CURATED_REPOSITORY' "
                        + "ORDER BY lower(owner_login), lower(repository_name), id",
                (result, row) -> map(result), namespaceId);
    }

    @Override
    public Optional<GithubDiscoveryEntry> findTeamRepository(long namespaceId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM github_discovery_entry "
                        + "WHERE namespace_id = ? AND id = ? AND entry_type = 'CURATED_REPOSITORY'",
                result -> result.next() ? Optional.of(map(result)) : Optional.empty(), namespaceId, id);
    }

    @Override
    public Optional<GithubDiscoveryEntry> findPlatformEntry(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM github_discovery_entry "
                        + "WHERE id = ? AND namespace_id = ?",
                result -> result.next() ? Optional.of(map(result)) : Optional.empty(),
                id, platformNamespaceId());
    }

    @Override
    public GithubDiscoveryEntry createSearchRule(String title, String criteriaJson,
                                                 int targetIntervalSeconds) {
        return returning("""
                INSERT INTO github_discovery_entry
                    (id, namespace_id, entry_type, title, criteria_json,
                     enabled, target_interval_seconds)
                VALUES (?, ?, 'SEARCH_RULE', ?, ?::jsonb, FALSE, ?)
                RETURNING """ + COLUMNS,
                UUID.randomUUID(), platformNamespaceId(), title, criteriaJson, targetIntervalSeconds);
    }

    @Override
    public GithubDiscoveryEntry updateSearchRule(UUID id, String title, String criteriaJson,
                                                 int targetIntervalSeconds, boolean enabled) {
        return returning("""
                UPDATE github_discovery_entry
                SET title = ?, criteria_json = ?::jsonb, target_interval_seconds = ?,
                    enabled = ?, rule_version = rule_version + 1,
                    next_due_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE next_due_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND namespace_id = ? AND entry_type = 'SEARCH_RULE'
                RETURNING """ + COLUMNS,
                title, criteriaJson, targetIntervalSeconds, enabled, enabled,
                id, platformNamespaceId());
    }

    @Override
    public GithubDiscoveryEntry createCuratedOwner(String ownerLogin, int targetIntervalSeconds) {
        return returning("""
                INSERT INTO github_discovery_entry
                    (id, namespace_id, entry_type, title, owner_login,
                     enabled, target_interval_seconds)
                VALUES (?, ?, 'CURATED_OWNER', ?, ?, TRUE, ?)
                RETURNING """ + COLUMNS,
                UUID.randomUUID(), platformNamespaceId(), ownerLogin, ownerLogin,
                targetIntervalSeconds);
    }

    @Override
    public GithubDiscoveryEntry createCuratedRepository(String ownerLogin, String repositoryName) {
        return returning("""
                INSERT INTO github_discovery_entry
                    (id, namespace_id, entry_type, title, owner_login, repository_name,
                     source_visibility, enabled, target_interval_seconds)
                VALUES (?, ?, 'CURATED_REPOSITORY', ?, ?, ?, 'PUBLIC', TRUE,
                        COALESCE((SELECT repository_hours * 3600 FROM github_sync_schedule_policy WHERE id = TRUE FOR SHARE), 86400))
                ON CONFLICT (namespace_id, lower(owner_login), lower(repository_name))
                    WHERE entry_type='CURATED_REPOSITORY'
                DO UPDATE SET id=github_discovery_entry.id
                RETURNING """ + COLUMNS,
                UUID.randomUUID(), platformNamespaceId(), repositoryName,
                ownerLogin, repositoryName);
    }

    @Override
    public GithubDiscoveryEntry createTeamRepository(long namespaceId, String ownerLogin,
                                                    String repositoryName, String visibility) {
        return returning("""
                INSERT INTO github_discovery_entry
                    (id, namespace_id, entry_type, title, owner_login, repository_name,
                     source_visibility, enabled, target_interval_seconds)
                VALUES (?, ?, 'CURATED_REPOSITORY', ?, ?, ?, ?, TRUE,
                        COALESCE((SELECT repository_hours * 3600 FROM github_sync_schedule_policy WHERE id = TRUE FOR SHARE), 86400))
                RETURNING """ + COLUMNS,
                UUID.randomUUID(), namespaceId, repositoryName, ownerLogin, repositoryName, visibility);
    }

    @Override
    @Transactional
    public GithubDiscoveryEntry setTeamRepositoryEnabled(long namespaceId, UUID id, boolean enabled) {
        GithubDiscoveryEntry entry = returning("""
                UPDATE github_discovery_entry
                SET enabled = ?, rule_version = rule_version + 1,
                    next_due_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE next_due_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND namespace_id = ? AND entry_type = 'CURATED_REPOSITORY'
                RETURNING """ + COLUMNS,
                enabled, enabled, id, namespaceId);
        jdbc.update("""
                UPDATE github_source_binding source
                SET status = CASE WHEN ? THEN 'ACTIVE' ELSE 'PAUSED' END,
                    next_check_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE next_check_at END,
                    updated_at = CURRENT_TIMESTAMP
                FROM github_source_origin origin
                WHERE origin.source_id = source.id
                  AND origin.origin_type = 'TEAM_CONFIG' AND origin.origin_key = ?
                  AND source.namespace_id = ?
                  AND (source.status = 'PAUSED' OR ? = FALSE)
                """, enabled, enabled, id.toString(), namespaceId, enabled);
        return entry;
    }

    @Override
    @Transactional
    public GithubDiscoveryEntry setTeamRepositoryVisibility(long namespaceId, UUID id, String visibility) {
        GithubDiscoveryEntry entry = returning("""
                UPDATE github_discovery_entry
                SET source_visibility = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND namespace_id = ? AND entry_type = 'CURATED_REPOSITORY'
                RETURNING """ + COLUMNS,
                visibility, id, namespaceId);
        jdbc.update("""
                UPDATE github_source_binding source
                SET visibility = ?, updated_at = CURRENT_TIMESTAMP
                FROM github_source_origin origin
                WHERE origin.source_id = source.id
                  AND origin.origin_type = 'TEAM_CONFIG' AND origin.origin_key = ?
                  AND source.namespace_id = ?
                """, visibility, id.toString(), namespaceId);
        return entry;
    }

    @Override
    public GithubDiscoveryEntry setEnabled(UUID id, boolean enabled) {
        return returning("""
                UPDATE github_discovery_entry
                SET enabled = ?, rule_version = rule_version + 1,
                    next_due_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE next_due_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND namespace_id = ?
                RETURNING """ + COLUMNS,
                enabled, enabled, id, platformNamespaceId());
    }

    @Override
    @Transactional
    public int enqueueDue(Instant now, int maxEntries) {
        if (maxEntries < 1 || maxEntries > 100) {
            throw new IllegalArgumentException("Discovery scheduler batch must contain 1 to 100 entries");
        }
        List<GithubDiscoveryEntry> due = jdbc.query("""
                SELECT entry.id, entry.namespace_id, entry.entry_type, entry.title,
                       entry.owner_login, entry.repository_name, entry.repository_id,
                       entry.source_visibility,
                       entry.criteria_json::text AS criteria_json,
                       entry.rule_version, entry.enabled, entry.target_interval_seconds,
                       entry.next_due_at, entry.created_at, entry.updated_at
                FROM github_discovery_entry entry
                WHERE entry.enabled = TRUE AND entry.next_due_at <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM github_sync_task task
                      WHERE task.namespace_id = entry.namespace_id
                        AND task.dedupe_key = 'discovery:' || entry.id::text
                        AND task.status IN ('QUEUED', 'LEASED', 'RETRY_WAIT'))
                ORDER BY entry.next_due_at, entry.id
                LIMIT ? FOR UPDATE OF entry SKIP LOCKED
                """, (result, row) -> map(result), Timestamp.from(now), maxEntries);
        int queued = 0;
        for (GithubDiscoveryEntry entry : due) {
            Integer budget = "CURATED_REPOSITORY".equals(entry.entryType())
                    ? 1 : availableCandidateBudget(entry.namespaceId());
            if (budget == null || budget < 1) {
                continue;
            }
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            String dedupeKey = "discovery:" + entry.id();
            String snapshot = snapshotJson(entry, dedupeKey, budget, now);
            jdbc.update("""
                    INSERT INTO github_sync_run (id, namespace_id, kind, status, created_at)
                    VALUES (?, ?, 'DISCOVERY', 'QUEUED', ?)
                    """, runId, entry.namespaceId(), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO github_sync_task
                        (id, run_id, namespace_id, kind, status, dedupe_key, priority,
                         snapshot_json, due_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'DISCOVERY', 'QUEUED', ?, ?, ?::jsonb, ?, ?, ?)
                    """, taskId, runId, entry.namespaceId(), dedupeKey,
                    "CURATED_REPOSITORY".equals(entry.entryType()) ? 90
                            : "CURATED_OWNER".equals(entry.entryType()) ? 80 : 20,
                    snapshot, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    UPDATE github_discovery_entry
                    SET next_due_at = ?, updated_at = ? WHERE id = ?
                    """, Timestamp.from(now.plusSeconds(entry.targetIntervalSeconds())),
                    Timestamp.from(now), entry.id());
            queued++;
        }
        return queued;
    }

    private Integer availableCandidateBudget(long namespaceId) {
        return jdbc.query("""
                SELECT least(policy.candidate_per_lease_limit,
                             policy.candidate_pending_limit - (
                                 SELECT count(*) FROM github_discovery_candidate candidate
                                 WHERE candidate.namespace_id = ?
                                   AND candidate.status IN ('PENDING', 'VALIDATING'))) AS budget
                FROM github_sync_admission_policy policy WHERE policy.id = TRUE
                """, result -> result.next() ? result.getInt("budget") : null, namespaceId);
    }

    private String snapshotJson(GithubDiscoveryEntry entry, String dedupeKey,
                                int candidateBudget, Instant now) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("entryType", entry.entryType());
        snapshot.put("ruleId", entry.id().toString());
        snapshot.put("ruleVersion", entry.ruleVersion());
        snapshot.put("candidateBudget", candidateBudget);
        if ("CURATED_OWNER".equals(entry.entryType())) {
            snapshot.put("owner", entry.ownerLogin());
        } else if ("CURATED_REPOSITORY".equals(entry.entryType())) {
            String owner = entry.ownerLogin();
            String name = entry.repositoryName();
            if (entry.repositoryId() != null) {
                List<RepositoryCoordinate> current = jdbc.query("""
                        SELECT owner_login, repository_name FROM github_source_binding
                        WHERE namespace_id = ? AND repository_id = ?
                        """, (result, row) -> new RepositoryCoordinate(
                        result.getString("owner_login"), result.getString("repository_name")),
                        entry.namespaceId(), entry.repositoryId());
                if (!current.isEmpty()) {
                    owner = current.getFirst().owner();
                    name = current.getFirst().name();
                }
            }
            snapshot.put("owner", owner);
            snapshot.put("repositoryName", name);
        } else {
            try {
                snapshot.set("criteria", objectMapper.readTree(entry.criteriaJson()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Persisted GitHub search rule is invalid", exception);
            }
            SuccessfulWindows windows = successfulWindows(entry.namespaceId(), dedupeKey,
                    entry.ruleVersion());
            ObjectNode window = snapshot.putObject("searchWindow");
            if (windows.lastFull() == null || windows.lastFull().isBefore(now.minus(Duration.ofDays(7)))
                    || windows.lastSuccess() == null) {
                window.put("mode", "FULL_RECHECK");
            } else {
                window.put("mode", "INCREMENTAL");
                Instant since = windows.lastSuccess().minus(Duration.ofHours(1));
                window.put("since", since.isAfter(now) ? now.toString() : since.toString());
            }
            window.put("until", now.toString());
        }
        return snapshot.toString();
    }

    private SuccessfulWindows successfulWindows(long namespaceId, String dedupeKey, long ruleVersion) {
        return jdbc.query("""
                SELECT max((snapshot_json #>> '{searchWindow,until}')::timestamptz) AS last_success,
                       max(CASE WHEN snapshot_json #>> '{searchWindow,mode}' = 'FULL_RECHECK'
                           THEN (snapshot_json #>> '{searchWindow,until}')::timestamptz END) AS last_full
                FROM github_sync_task
                WHERE namespace_id = ? AND dedupe_key = ?
                  AND status = 'ACQUISITION_COMPLETE'
                  AND (snapshot_json ->> 'ruleVersion')::bigint = ?
                """, result -> result.next()
                ? new SuccessfulWindows(
                        result.getTimestamp("last_success") == null ? null
                                : result.getTimestamp("last_success").toInstant(),
                        result.getTimestamp("last_full") == null ? null
                                : result.getTimestamp("last_full").toInstant())
                : new SuccessfulWindows(null, null), namespaceId, dedupeKey, ruleVersion);
    }

    private record SuccessfulWindows(Instant lastSuccess, Instant lastFull) {}

    private record RepositoryCoordinate(String owner, String name) {}

    private GithubDiscoveryEntry returning(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.query(sql,
                result -> result.next() ? map(result) : null, args),
                "GitHub discovery entry was not found");
    }

    private long platformNamespaceId() {
        Long id = jdbc.queryForObject("""
                SELECT id FROM namespace WHERE slug = 'github' AND type = 'CATALOG'
                """, Long.class);
        return Objects.requireNonNull(id, "@github catalog namespace is missing");
    }

    private static GithubDiscoveryEntry map(ResultSet result) throws SQLException {
        long repositoryId = result.getLong("repository_id");
        Long nullableRepositoryId = result.wasNull() ? null : repositoryId;
        return new GithubDiscoveryEntry(
                result.getObject("id", UUID.class), result.getLong("namespace_id"),
                result.getString("entry_type"), result.getString("title"),
                result.getString("owner_login"), result.getString("repository_name"),
                nullableRepositoryId, result.getString("source_visibility"),
                result.getString("criteria_json"),
                result.getLong("rule_version"), result.getBoolean("enabled"),
                result.getInt("target_interval_seconds"), result.getTimestamp("next_due_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }
}
