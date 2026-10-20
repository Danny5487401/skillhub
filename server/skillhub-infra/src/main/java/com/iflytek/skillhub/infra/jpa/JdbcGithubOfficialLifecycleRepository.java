package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubDiscoveryBatch;
import com.iflytek.skillhub.githubsync.GithubDiscoveryCandidate;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryBatch;
import com.iflytek.skillhub.githubsync.GithubOfficialLifecycleRepository;
import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.githubsync.GithubRepositoryCompletionResult;
import com.iflytek.skillhub.githubsync.GithubSyncDeferralReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Official page acceptance and durable progress share the lease transaction. */
@Repository
@Transactional
public class JdbcGithubOfficialLifecycleRepository implements GithubOfficialLifecycleRepository {
    private final JdbcTemplate jdbc;
    private final JdbcGithubDiscoveryOutcomeRepository outcomes;
    private final ObjectMapper mapper;
    private final GithubSyncLeaseService leases;
    private final JdbcGithubSyncCapacityGate capacity;
    private final JdbcGithubOfficialShardRepository shards;
    private final JdbcGithubOfficialQuotaRepository quota;

    public JdbcGithubOfficialLifecycleRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                                 GithubSyncLeaseService leases, JdbcGithubSyncCapacityGate capacity,
                                                 JdbcGithubOfficialShardRepository shards) {
        this.jdbc = jdbc;
        this.outcomes = new JdbcGithubDiscoveryOutcomeRepository(jdbc);
        this.mapper = mapper;
        this.leases = leases;
        this.capacity = capacity;
        this.shards = shards;
        this.quota = new JdbcGithubOfficialQuotaRepository(jdbc);
    }

    @Override
    public boolean isOfficialDiscovery(UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM github_sync_task "
                + "WHERE id = ? AND official_query_id IS NOT NULL)", Boolean.class, taskId));
    }

    @Override
    public void report(UUID taskId, long generation, String token, GithubDiscoveryBatch batch,
                       GithubOfficialDiscoveryBatch metadata) {
        Configuration config = configuration();
        Lease lease = lease(taskId, generation, token);
        JsonNode checkpoint = checkpoint(batch.checkpoint(), lease.snapshot());
        validateMetadata(metadata, checkpoint, lease);
        List<GithubDiscoveryCandidate> reportedRepositories = batch.repositories().stream()
                .sorted(Comparator.comparingLong(GithubDiscoveryCandidate::repositoryId)).toList();
        for (var candidate : reportedRepositories) {
            validateProvenance(candidate, lease);
        }
        String digest = hash(json(List.of(batch.previousCheckpoint() == null ? "" : batch.previousCheckpoint(),
                batch.checkpoint(), reportedRepositories, batch.deferredCount(),
                batch.deferralReason() == null ? "" : batch.deferralReason().name(), metadata)));
        String prior = jdbc.query("SELECT request_sha256 FROM github_discovery_batch WHERE task_id = ? AND checkpoint = ?",
                result -> result.next() ? result.getString(1) : null, taskId, batch.checkpoint());
        if (prior != null) {
            require(prior.equals(digest), 409, "RESULT_CONFLICT");
            return;
        }
        if (metadata.ownerLogin() != null) {
            jdbc.update("UPDATE github_deep_discovery_query SET owner_login = ? WHERE id = ? AND query_source = 'OWNER'",
                    metadata.ownerLogin(), lease.queryId());
        }
        boolean ownerBlocked = ownerExcluded(lease);
        boolean controlAllowed = config.enabled() && !ownerBlocked;
        if (!controlAllowed) {
            boolean alreadyAccepted = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM github_discovery_batch WHERE task_id = ?
                        AND official_metadata ->> 'generation' = ?
                        AND official_metadata ->> 'configurationVersion' = ?
                        AND COALESCE(official_metadata ->> 'controlAllowed', official_metadata ->> 'configurationEnabled') = 'false')
                    """, Boolean.class, taskId, Long.toString(generation), Long.toString(config.version())));
            require(!alreadyAccepted, 409, "BACKPRESSURE_ACTIVE");
        }
        require(Objects.equals(lease.checkpoint(), batch.previousCheckpoint()), 409, "CHECKPOINT_CONFLICT");
        // A completed shard cannot be turned back into an unfinished page by a later report.
        if (lease.checkpoint() != null) {
            require(!List.of("COMPLETE", "SPLIT").contains(checkpoint(lease.checkpoint(), lease.snapshot()).path("coverage").asText()),
                    409, "CHECKPOINT_CONFLICT");
        }
        int requests = jdbc.queryForObject("""
                SELECT COALESCE(sum((official_metadata ->> 'requestsUsed')::integer), 0)
                FROM github_discovery_batch WHERE task_id = ? AND official_metadata ->> 'generation' = ?
                """, Integer.class, taskId, Long.toString(generation));
        require(requests + metadata.requestsUsed() <= lease.snapshot().path("budget").path("maxRequests").asInt(),
                422, "CANDIDATE_BUDGET_EXCEEDED");
        var reportedIds = new HashSet<>(jdbc.query("""
                SELECT value::bigint AS repository_id
                FROM github_discovery_batch batch,
                    jsonb_array_elements_text(COALESCE(batch.official_metadata -> 'reportedRepositoryIds', '[]'::jsonb))
                WHERE task_id = ? AND official_metadata ->> 'generation' = ?
                UNION SELECT repository_id FROM github_discovery_task_hit WHERE task_id = ? AND lease_generation = ?
                """, (result, row) -> result.getLong("repository_id"), taskId, Long.toString(generation), taskId, generation));
        reportedRepositories.forEach(candidate -> reportedIds.add(candidate.repositoryId()));
        require(reportedIds.size() <= lease.snapshot().path("budget").path("candidateBudget").asInt(),
                422, "CANDIDATE_BUDGET_EXCEEDED");
        // Advance the page for a valid response while excluding new official discovery at current receipt policy.
        List<GithubDiscoveryCandidate> repositories = reportedRepositories.stream()
                .filter(candidate -> !ownerBlocked && !excluded(candidate, config.exclusions())).toList();
        // Lock namespace before candidates, matching the legacy discovery acceptance order.
        jdbc.queryForObject("SELECT id FROM namespace WHERE id = ? FOR UPDATE", Long.class, lease.namespaceId());
        int pendingLimit = jdbc.queryForObject("SELECT candidate_pending_limit FROM github_sync_admission_policy WHERE id = TRUE", Integer.class);
        long pending = jdbc.queryForObject("SELECT count(*) FROM github_discovery_candidate WHERE namespace_id = ? "
                + "AND status IN ('PENDING','VALIDATING')", Long.class, lease.namespaceId());
        long hits = jdbc.queryForObject("SELECT count(*) FROM github_discovery_task_hit WHERE task_id = ? AND lease_generation = ?",
                Long.class, taskId, generation);
        int newHits = 0;
        int newPending = 0;
        for (var candidate : repositories) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM github_discovery_task_hit "
                    + "WHERE task_id = ? AND repository_id = ? AND lease_generation = ?)", Boolean.class, taskId, candidate.repositoryId(), generation))) {
                newHits++;
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM github_discovery_candidate WHERE namespace_id = ? AND repository_id = ?)
                        OR EXISTS (SELECT 1 FROM github_source_binding WHERE namespace_id = ? AND repository_id = ?)
                    """, Boolean.class, lease.namespaceId(), candidate.repositoryId(), lease.namespaceId(), candidate.repositoryId()))) {
                newPending++;
            }
        }
        require(hits + newHits <= lease.snapshot().path("budget").path("candidateBudget").asInt()
                        && (newPending == 0 || pending + newPending <= pendingLimit),
                422, "CANDIDATE_BUDGET_EXCEEDED");
        Instant now = Instant.now();
        for (var candidate : reportedRepositories) {
            if (excluded(candidate, config.exclusions())) {
                // Keep an existing lead's current coordinates usable by live exclusion checks, without
                // creating a candidate or granting it a new official origin.
                jdbc.update("""
                        UPDATE github_discovery_candidate SET owner_login = ?, repository_name = ?, default_branch = ?,
                            last_seen_at = ? WHERE namespace_id = ? AND repository_id = ?
                        """, candidate.owner(), candidate.name(), candidate.defaultBranch(), Timestamp.from(now),
                        lease.namespaceId(), candidate.repositoryId());
            }
        }
        for (var candidate : repositories) {
            Long id = jdbc.query("""
                    INSERT INTO github_discovery_candidate
                        (namespace_id, repository_id, owner_login, repository_name, default_branch, first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (namespace_id, repository_id) DO NOTHING RETURNING id
                    """, result -> result.next() ? result.getLong("id") : null,
                    lease.namespaceId(), candidate.repositoryId(), candidate.owner(), candidate.name(),
                    candidate.defaultBranch(), Timestamp.from(now), Timestamp.from(now));
            boolean created = id != null;
            if (!created) {
                id = jdbc.queryForObject("""
                        UPDATE github_discovery_candidate SET owner_login = ?, repository_name = ?, default_branch = ?,
                            last_seen_at = ? WHERE namespace_id = ? AND repository_id = ? RETURNING id
                        """, Long.class, candidate.owner(), candidate.name(), candidate.defaultBranch(), Timestamp.from(now),
                        lease.namespaceId(), candidate.repositoryId());
            }
            jdbc.update("""
                    INSERT INTO github_discovery_task_hit (task_id, repository_id, candidate_id, lease_generation, created_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (task_id, repository_id) DO UPDATE SET lease_generation = EXCLUDED.lease_generation
                    """, taskId, candidate.repositoryId(), id, generation, Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO github_discovery_candidate_origin
                        (candidate_id, origin_type, origin_key, first_seen_at, last_seen_at)
                    VALUES (?, 'OFFICIAL_DISCOVERY', ?, ?, ?)
                    ON CONFLICT (candidate_id, origin_type, origin_key) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                    """, id, lease.queryId().toString(), Timestamp.from(now), Timestamp.from(now));
            outcomes.provenance(id, lease.queryId(), lease.snapshot().path("strategyVersion").asText(),
                    taskId, lease.runId(), owner(lease.snapshot()) ? lease.snapshot().path("seedRepositoryId").asLong() : null);
            if (created) {
                outcomes.candidateCreated(lease.namespaceId(), candidate.repositoryId(), now);
            }
            jdbc.update("""
                    INSERT INTO github_source_origin (source_id, origin_type, origin_key)
                    SELECT id, 'OFFICIAL_DISCOVERY', ? FROM github_source_binding WHERE namespace_id = ? AND repository_id = ?
                    ON CONFLICT (source_id, origin_type, origin_key) DO NOTHING
                    """, lease.queryId().toString(), lease.namespaceId(), candidate.repositoryId());
            jdbc.update("""
                    UPDATE github_discovery_candidate SET status = 'BOUND', last_error_code = NULL WHERE id = ?
                    AND EXISTS (SELECT 1 FROM github_source_binding WHERE namespace_id = ? AND repository_id = ?)
                    """, id, lease.namespaceId(), candidate.repositoryId());
        }
        if ("SPLIT".equals(metadata.coverage())) {
            shards.split(taskId, lease.queryId(), lease.snapshot(), metadata.childRanges());
        }
        ObjectNode persistedMetadata = mapper.valueToTree(metadata);
        persistedMetadata.put("generation", generation);
        persistedMetadata.put("configurationVersion", config.version());
        persistedMetadata.put("configurationEnabled", config.enabled());
        persistedMetadata.put("controlAllowed", controlAllowed);
        persistedMetadata.put("excludedCount", reportedRepositories.size() - repositories.size());
        var reportedIdsJson = persistedMetadata.putArray("reportedRepositoryIds");
        reportedRepositories.forEach(candidate -> reportedIdsJson.add(candidate.repositoryId()));
        jdbc.update("""
                INSERT INTO github_discovery_batch (task_id, checkpoint, request_sha256, deferred_count,
                    deferral_reason, official_metadata, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, taskId, batch.checkpoint(), digest, batch.deferredCount(),
                batch.deferralReason() == null ? null : batch.deferralReason().name(), persistedMetadata.toString(), Timestamp.from(now));
        jdbc.update("UPDATE github_sync_task SET discovery_checkpoint = ?, last_progress_at = ?, updated_at = ? WHERE id = ?",
                batch.checkpoint(), Timestamp.from(now), Timestamp.from(now), taskId);
        jdbc.update("UPDATE github_deep_discovery_query SET checkpoint = ?, last_progress_at = ?, wait_reason = ?, gap_reason = COALESCE(?, gap_reason) WHERE id = ?",
                batch.checkpoint(), Timestamp.from(now), config.enabled() ? ("GAP".equals(metadata.coverage()) ? metadata.gapReason() : metadata.coverage()) : "DISABLED", metadata.gapReason(), lease.queryId());
    }

    @Override
    public Control control(UUID taskId, long generation, String token) {
        Configuration config = configuration();
        if (isOfficialDiscovery(taskId)) {
            Lease discovery = lease(taskId, generation, token);
            String reason = !config.enabled() ? "DISABLED" : ownerExcluded(discovery) ? "EXCLUDED" : null;
            Control cooldown = quotaControl(taskId, config);
            return reason == null && cooldown != null ? cooldown
                    : new Control(reason == null ? "CONTINUE" : "PAUSE", config.version(), reason, null);
        }
        RepositoryLease repository = repositoryLease(taskId, generation, token);
        return repositoryControl(taskId, repository, config);
    }

    @Override
    public void pauseDiscovery(UUID taskId, long generation, String token, String checkpoint) {
        Configuration config = configuration();
        Lease lease = lease(taskId, generation, token);
        String reason = !config.enabled() ? "DISABLED" : ownerExcluded(lease) ? "EXCLUDED" : null;
        Control cooldown = quotaControl(taskId, config);
        if (reason == null && cooldown != null) {
            reason = cooldown.reason();
        }
        require(reason != null, 409, "CONTROL_CHANGED");
        require(Objects.equals(checkpoint, lease.checkpoint()), 409, "CHECKPOINT_CONFLICT");
        boolean deferred = "QUOTA_COOLDOWN".equals(reason);
        release(taskId, deferred ? "RETRY_WAIT" : "QUEUED", deferred ? cooldown.retryAt() : Instant.now(), reason);
        jdbc.update("UPDATE github_deep_discovery_query SET wait_reason = ? WHERE id = ?", reason, lease.queryId());
    }

    @Override
    public void pauseRepository(UUID taskId, long generation, String token, RepositoryProgress progress) {
        Configuration config = configuration();
        // Same order as package admission; no new admission can slip into a paused lease.
        jdbc.queryForObject("SELECT id FROM github_sync_admission_policy WHERE id = TRUE FOR UPDATE", Boolean.class);
        RepositoryLease repository = repositoryLease(taskId, generation, token);
        Control control = repositoryControl(taskId, repository, config);
        require("PAUSE".equals(control.action()), 409, "CONTROL_CHANGED");
        require(progress != null && progress.incompleteSubtrees() != null && progress.pathResults() != null,
                422, "RESULT_CONFLICT");
        if (progress.observedCommitSha() == null) {
            require(progress.pathCheckpoint() == null && progress.incompleteSubtrees().isEmpty()
                    && progress.pathResults().isEmpty() && repository.fixedCommit() == null
                    && repository.snapshotCommit() == null && repository.pathCheckpoint() == null,
                    409, "COMMIT_MISMATCH");
            boolean started = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM github_sync_package_admission WHERE task_id = ?)
                        OR EXISTS (SELECT 1 FROM github_sync_package_ingestion WHERE task_id = ?)
                        OR EXISTS (SELECT 1 FROM github_sync_path_result WHERE task_id = ?)
                    """, Boolean.class, taskId, taskId, taskId));
            require(!started, 409, "COMMIT_MISMATCH");
        } else {
            // Reuse established commit/path/ingestion validation. This internal future retry is replaced
            // by the paused state in the same transaction; old complete API semantics are unchanged.
            GithubRepositoryCompletionResult result = leases.completeRepository(taskId, generation, token,
                    progress.observedCommitSha(), false, progress.pathCheckpoint(), progress.incompleteSubtrees(),
                    progress.pathResults(), Instant.now().plusSeconds(60), GithubSyncDeferralReason.CAPACITY);
            require(result == GithubRepositoryCompletionResult.RETRY_WAIT, 409, result.name());
        }
        boolean deferred = "QUOTA_COOLDOWN".equals(control.reason());
        release(taskId, deferred ? "RETRY_WAIT" : "QUEUED", deferred ? control.retryAt() : Instant.now(), control.reason());
    }

    private RepositoryLease repositoryLease(UUID taskId, long generation, String token) {
        require(token != null && !token.isBlank() && generation > 0, 409, "LEASE_STALE");
        RepositoryLease repository = jdbc.query("""
                SELECT namespace_id, source_id, (snapshot_json ->> 'repositoryId')::bigint AS repository_id,
                    fixed_commit_sha, snapshot_json ->> 'fixedCommitSha' AS snapshot_commit, path_checkpoint
                FROM github_sync_task WHERE id = ? AND kind = 'REPOSITORY_SYNC' AND mode = 'FULL'
                    AND status = 'LEASED' AND lease_generation = ? AND lease_token_hash = ? AND lease_expires_at > now()
                    AND required_capabilities @> ARRAY['official-pause/v1'] FOR UPDATE
                """, result -> result.next() ? new RepositoryLease(result.getLong("namespace_id"),
                (Long) result.getObject("source_id"), result.getLong("repository_id"), result.getString("fixed_commit_sha"),
                result.getString("snapshot_commit"), result.getString("path_checkpoint")) : null,
                taskId, generation, hash(token));
        require(repository != null, 409, "LEASE_STALE");
        return repository;
    }

    private Control repositoryControl(UUID taskId, RepositoryLease repository, Configuration config) {
        // Include a source created after this candidate task was leased.
        String sourceStatus = jdbc.query("""
                SELECT status FROM github_source_binding WHERE namespace_id = ? AND repository_id = ? FOR SHARE
                """, result -> result.next() ? result.getString(1) : null, repository.namespaceId(), repository.repositoryId());
        if ("PAUSED".equals(sourceStatus)) {
            return new Control("PAUSE", config.version(), "SOURCE_PAUSED", null);
        }
        Control cooldown = quotaControl(taskId, config);
        if (cooldown != null) {
            return cooldown;
        }
        if (sourceStatus != null) {
            return new Control("CONTINUE", config.version(), null, null);
        }
        Eligibility eligibility = jdbc.query("""
                SELECT has_official, has_independent, configuration_enabled, excluded
                FROM github_candidate_discovery_eligibility WHERE namespace_id = ? AND repository_id = ?
                """, result -> result.next() ? new Eligibility(result.getBoolean(1), result.getBoolean(2),
                result.getBoolean(3), result.getBoolean(4)) : null, repository.namespaceId(), repository.repositoryId());
        require(eligibility != null && eligibility.official(), 409, "CONTROL_CHANGED");
        if (eligibility.independent()) {
            return new Control("CONTINUE", config.version(), null, null);
        }
        String reason = !eligibility.enabled() ? "DISABLED" : eligibility.excluded() ? "EXCLUDED"
                : !capacity.allowsFullAcquisition(repository.namespaceId(), Instant.now()) ? "REVIEW_BACKPRESSURE" : null;
        return new Control(reason == null ? "CONTINUE" : "PAUSE", config.version(), reason, null);
    }

    @Override
    public UUID recover(UUID taskId, long generation, String token, String reason, String rejectedCheckpoint) {
        configuration();
        Lease lease = lease(taskId, generation, token);
        require(List.of("MALFORMED", "FORMAT_UNSUPPORTED", "IDENTITY_MISMATCH").contains(reason == null ? "" : reason)
                && rejectedCheckpoint != null, 422, "RESULT_CONFLICT");
        require(Objects.equals(rejectedCheckpoint, lease.checkpoint()), 409, "CHECKPOINT_CONFLICT");
        UUID replacement = UUID.randomUUID();
        UUID replacementRun = UUID.randomUUID();
        ObjectNode snapshot = lease.snapshot().deepCopy();
        snapshot.put("shardId", replacement.toString());
        snapshot.remove("checkpoint");
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("officialDiscovery", snapshot);
        // Release the partial unique open-work key before inserting the replacement in this transaction.
        release(taskId, "FAILED", Instant.now(), "CHECKPOINT_RECOVERY");
        jdbc.update("INSERT INTO github_sync_run (id, namespace_id, kind, status) VALUES (?, ?, 'DISCOVERY', 'QUEUED')",
                replacementRun, lease.namespaceId());
        jdbc.update("""
                INSERT INTO github_sync_task (id, run_id, namespace_id, kind, status, dedupe_key, priority,
                    snapshot_json, due_at, created_at, updated_at, required_capabilities, official_query_id)
                SELECT ?, ?, namespace_id, kind, 'QUEUED', dedupe_key, priority, ?::jsonb,
                    now(), now(), now(), required_capabilities, official_query_id FROM github_sync_task WHERE id = ?
                """, replacement, replacementRun, wrapper.toString(), taskId);
        if (!owner(lease.snapshot())) {
            shards.recover(taskId, replacement, lease.queryId(), lease.snapshot());
        }
        jdbc.update("UPDATE github_deep_discovery_query SET active_task_id = ?, checkpoint = NULL, wait_reason = 'CHECKPOINT_RECOVERY' WHERE id = ?",
                replacement, lease.queryId());
        return replacement;
    }

    @Override
    public String complete(UUID taskId, long generation, String token, String checkpoint,
                           String coverage, Instant retryAt) {
        configuration();
        Lease lease = lease(taskId, generation, token);
        require(checkpoint != null && Objects.equals(checkpoint, lease.checkpoint()), 409, "CHECKPOINT_CONFLICT");
        String savedCoverage = checkpoint(checkpoint, lease.snapshot()).path("coverage").asText();
        Instant now = Instant.now();
        if ("COMPLETE".equals(coverage)) {
            require(List.of("COMPLETE", "SPLIT").contains(savedCoverage) && retryAt == null, 409, "RESULT_CONFLICT");
            if (owner(lease.snapshot())) {
                int hours = jdbc.queryForObject("SELECT search_rule_hours FROM github_sync_schedule_policy WHERE id = TRUE", Integer.class);
                release(taskId, "ACQUISITION_COMPLETE", now, null);
                jdbc.update("""
                        UPDATE github_deep_discovery_query SET active_task_id = NULL, checkpoint = NULL,
                            last_full_recheck_at = ?, next_due_at = ?, last_progress_at = ?, wait_reason = NULL, gap_reason = NULL
                        WHERE id = ?
                        """, Timestamp.from(now), Timestamp.from(now.plusSeconds(hours * 3600L)), Timestamp.from(now), lease.queryId());
                return "ACQUISITION_COMPLETE";
            }
            var root = shards.finish(taskId, lease.queryId(), lease.snapshot(), "SPLIT".equals(savedCoverage));
            if (root == null) {
                release(taskId, "ACQUISITION_COMPLETE", now, "SHARD_HANDOFF");
                jdbc.update("""
                        UPDATE github_deep_discovery_query SET active_task_id = NULL, checkpoint = NULL,
                            next_due_at = ?, last_progress_at = ?, wait_reason = 'SHARD_PENDING' WHERE id = ?
                        """, Timestamp.from(shards.nextDue(lease.queryId(), now)), Timestamp.from(now), lease.queryId());
                return "ACQUISITION_COMPLETE";
            }
            Instant until = root.until();
            int hours = jdbc.queryForObject("SELECT search_rule_hours FROM github_sync_schedule_policy WHERE id = TRUE", Integer.class);
            release(taskId, "ACQUISITION_COMPLETE", now, null);
            jdbc.update("""
                    UPDATE github_deep_discovery_query SET covered_until = GREATEST(covered_until, ?),
                        active_task_id = NULL, checkpoint = NULL, next_due_at = ?, last_progress_at = ?, wait_reason = NULL, gap_reason = NULL,
                        last_full_recheck_at = CASE WHEN ? THEN ? ELSE last_full_recheck_at END WHERE id = ?
                    """, Timestamp.from(until), Timestamp.from(now.plusSeconds(hours * 3600L)), Timestamp.from(now),
                    root.historical(), Timestamp.from(now), lease.queryId());
            return "ACQUISITION_COMPLETE";
        }
        require("INCOMPLETE".equals(coverage) && !List.of("COMPLETE", "SPLIT").contains(savedCoverage)
                && retryAt != null && retryAt.isAfter(now), 422, "RESULT_CONFLICT");
        String waitReason = "GAP".equals(savedCoverage)
                ? jdbc.queryForObject("SELECT official_metadata ->> 'gapReason' FROM github_discovery_batch WHERE task_id = ? AND checkpoint = ?",
                        String.class, taskId, checkpoint) : savedCoverage;
        if ("GAP".equals(savedCoverage) && !owner(lease.snapshot())) {
            shards.gap(taskId, lease.queryId(), lease.snapshot(), checkpoint, generation, retryAt, waitReason);
            release(taskId, "PARTIAL", retryAt, waitReason);
            jdbc.update("""
                    UPDATE github_deep_discovery_query SET active_task_id = NULL, checkpoint = NULL,
                        next_due_at = ?, last_progress_at = ?, wait_reason = ? WHERE id = ?
                    """, Timestamp.from(shards.nextDue(lease.queryId(), now)), Timestamp.from(now), waitReason, lease.queryId());
            return "PARTIAL";
        }
        release(taskId, "RETRY_WAIT", retryAt, savedCoverage);
        jdbc.update("UPDATE github_deep_discovery_query SET wait_reason = ? WHERE id = ?", waitReason, lease.queryId());
        return "RETRY_WAIT";
    }

    private void release(UUID taskId, String status, Instant due, String reason) {
        jdbc.update("""
                UPDATE github_sync_task SET status = ?, due_at = ?, retry_at = ?, official_pause_reason = ?,
                    lease_token_hash = NULL, lease_expires_at = NULL, leased_by = NULL,
                    completed_at = CASE WHEN ? IN ('FAILED', 'ACQUISITION_COMPLETE', 'PARTIAL') THEN now() ELSE NULL END,
                    updated_at = now() WHERE id = ?
                """, status, Timestamp.from(due), "RETRY_WAIT".equals(status) ? Timestamp.from(due) : null, reason, status, taskId);
    }

    private boolean excluded(GithubDiscoveryCandidate candidate, JsonNode exclusions) {
        String owner = candidate.owner().toLowerCase(Locale.ROOT);
        String repository = owner + "/" + candidate.name().toLowerCase(Locale.ROOT);
        for (JsonNode exclusion : exclusions) {
            if ("OWNER".equals(exclusion.path("kind").asText()) && owner.equals(exclusion.path("value").asText())
                    || "REPOSITORY".equals(exclusion.path("kind").asText()) && repository.equals(exclusion.path("value").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseExclusions(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid stored official exclusions", exception);
        }
    }

    private Configuration configuration() {
        return jdbc.query("SELECT enabled, config_version, exclusions_json::text FROM github_deep_discovery_config WHERE id = TRUE FOR SHARE",
                result -> {
                    if (!result.next()) {
                        throw new IllegalStateException("Official discovery configuration is missing");
                    }
                    return new Configuration(result.getBoolean(1), result.getLong(2), parseExclusions(result.getString(3)));
                });
    }

    private Lease lease(UUID taskId, long generation, String token) {
        require(token != null && !token.isBlank() && generation > 0, 409, "LEASE_STALE");
        Lease lease = jdbc.query("""
                SELECT task.namespace_id, task.run_id, task.official_query_id, task.snapshot_json::text,
                    task.discovery_checkpoint FROM github_sync_task task
                JOIN github_deep_discovery_query query ON query.id = task.official_query_id AND query.active_task_id = task.id
                WHERE task.id = ? AND task.kind = 'DISCOVERY' AND task.status = 'LEASED' AND task.lease_generation = ?
                    AND task.lease_token_hash = ? AND task.lease_expires_at > now() FOR UPDATE OF task
                """, result -> result.next() ? new Lease(result.getLong(1), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), parse(result.getString(4)).path("officialDiscovery"), result.getString(5)) : null,
                taskId, generation, hash(token));
        require(lease != null, 409, "LEASE_STALE");
        return lease;
    }

    private JsonNode checkpoint(String value, JsonNode snapshot) {
        require(value != null && value.length() <= 4096 && value.getBytes(StandardCharsets.UTF_8).length <= 4096,
                422, "RESULT_CONFLICT");
        JsonNode result = parse(value);
        require(result.path("formatVersion").isIntegralNumber() && result.path("formatVersion").asInt() == 1
                && result.path("nextPage").isIntegralNumber() && result.path("nextPage").canConvertToInt()
                && result.path("nextPage").asInt() >= 1 && (owner(snapshot) || result.path("nextPage").asInt() <= 11)
                && (!result.has("cursor") || result.path("cursor").isTextual() && result.path("cursor").asText().length() <= 1024)
                && List.of("IN_PROGRESS", "COMPLETE", "GAP", "SPLIT").contains(result.path("coverage").asText()),
                422, "RESULT_CONFLICT");
        for (String field : List.of("strategyVersion", "queryId", "queryVersion", "shardId")) {
            require(Objects.equals(snapshot.get(field), result.get(field)), 409, "CHECKPOINT_CONFLICT");
        }
        require(!owner(snapshot) || !"SPLIT".equals(result.path("coverage").asText()), 422, "RESULT_CONFLICT");
        return result;
    }

    private void validateMetadata(GithubOfficialDiscoveryBatch metadata, JsonNode checkpoint, Lease lease) {
        require(metadata != null && metadata.shardId() != null
                && metadata.shardId().toString().equals(lease.snapshot().path("shardId").asText())
                && Objects.equals(metadata.coverage(), checkpoint.path("coverage").asText())
                && metadata.requestsUsed() != null && metadata.requestsUsed() >= 0 && metadata.requestsUsed() <= 10
                && ("SPLIT".equals(metadata.coverage())
                    ? metadata.childRanges() != null && metadata.childRanges().size() == 2
                    : metadata.childRanges() == null || metadata.childRanges().isEmpty()), 422, "RESULT_CONFLICT");
        require(metadata.ownerLogin() == null || owner(lease.snapshot())
                && metadata.ownerLogin().matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"), 422, "RESULT_CONFLICT");
        require("GAP".equals(metadata.coverage())
                        ? List.of("RESULT_LIMIT", "INCOMPLETE_RESULTS", "UNSPLITTABLE", "WORK_BUDGET").contains(metadata.gapReason() == null ? "" : metadata.gapReason())
                        : metadata.gapReason() == null,
                422, "RESULT_CONFLICT");
    }

    private void validateProvenance(GithubDiscoveryCandidate candidate, Lease lease) {
        require(candidate.officialProvenance() != null && candidate.officialProvenance().size() == 1, 422, "RESULT_CONFLICT");
        var provenance = candidate.officialProvenance().getFirst();
        JsonNode snapshot = lease.snapshot();
        require(Objects.equals(provenance.queryId(), lease.queryId())
                && provenance.queryVersion() == snapshot.path("queryVersion").asLong()
                && Objects.equals(provenance.strategyVersion(), snapshot.path("strategyVersion").asText())
                && Objects.equals(provenance.channel(), snapshot.path("channel").asText())
                && provenance.shardId() != null && provenance.shardId().toString().equals(snapshot.path("shardId").asText())
                && (owner(snapshot) ? Objects.equals(provenance.seedRepositoryId(), snapshot.path("seedRepositoryId").asLong())
                    : provenance.seedRepositoryId() == null), 422, "RESULT_CONFLICT");
    }

    private Control quotaControl(UUID taskId, Configuration config) {
        Instant until = quota.cooldownForLease(taskId, Instant.now());
        return until == null ? null : new Control("PAUSE", config.version(), "QUOTA_COOLDOWN", until);
    }

    private boolean owner(JsonNode snapshot) {
        return "OWNER_EXPANSION".equals(snapshot.path("channel").asText());
    }

    private boolean ownerExcluded(Lease lease) {
        return owner(lease.snapshot()) && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM github_official_owner_eligibility WHERE query_id = ? AND excluded)
                """, Boolean.class, lease.queryId()));
    }

    private JsonNode parse(String value) {
        try {
            JsonNode parsed = mapper.readTree(value);
            require(parsed != null && parsed.isObject(), 422, "RESULT_CONFLICT");
            return parsed;
        } catch (Exception exception) {
            throw new GithubOfficialProtocolException(422, "RESULT_CONFLICT");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid official discovery result", exception);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void require(boolean valid, int status, String code) {
        if (!valid) {
            throw new GithubOfficialProtocolException(status, code);
        }
    }

    private record RepositoryLease(long namespaceId, Long sourceId, long repositoryId,
                                   String fixedCommit, String snapshotCommit, String pathCheckpoint) {}
    private record Eligibility(boolean official, boolean independent, boolean enabled, boolean excluded) {}
    private record Configuration(boolean enabled, long version, JsonNode exclusions) {}
    private record Lease(long namespaceId, UUID runId, UUID queryId, JsonNode snapshot, String checkpoint) {}
}
