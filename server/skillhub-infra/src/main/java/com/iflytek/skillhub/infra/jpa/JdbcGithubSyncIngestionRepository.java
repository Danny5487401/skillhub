package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSyncIngestionRepository;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGithubSyncIngestionRepository implements GithubSyncIngestionRepository {
    private final JdbcTemplate jdbc;
    private final JdbcGithubDiscoveryOutcomeRepository outcomes;
    private final JdbcGithubSyncSourceCheckpointRepository checkpoints;
    private final GithubSyncSchedulePolicyRepository schedulePolicies;
    private final Duration repositoryCheckInterval;

    public JdbcGithubSyncIngestionRepository(JdbcTemplate jdbc,
                                             JdbcGithubSyncSourceCheckpointRepository checkpoints,
                                             GithubSyncSchedulePolicyRepository schedulePolicies,
                                             @Value("${skillhub.github-sync.repository-check-interval:PT24H}")
                                             Duration repositoryCheckInterval) {
        this.jdbc = jdbc;
        this.outcomes = new JdbcGithubDiscoveryOutcomeRepository(jdbc);
        this.checkpoints = checkpoints;
        this.schedulePolicies = schedulePolicies;
        if (repositoryCheckInterval.isZero() || repositoryCheckInterval.isNegative()) {
            throw new IllegalArgumentException("GitHub repository check interval must be positive");
        }
        this.repositoryCheckInterval = repositoryCheckInterval;
    }

    @Override
    @Transactional
    public Optional<ClaimedIngestion> claim(UUID validationToken, Instant now, Instant expiresAt) {
        return jdbc.query("""
                WITH picked AS (
                    SELECT id FROM github_sync_package_ingestion
                    WHERE status = 'RECEIVED'
                       OR (status = 'RETRY_WAIT' AND retry_at <= ?)
                       OR (status = 'VALIDATING' AND validation_expires_at <= ?)
                    ORDER BY created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE github_sync_package_ingestion AS ingestion
                SET status = 'VALIDATING', validation_token = ?, validation_expires_at = ?,
                    validation_attempts = ingestion.validation_attempts + 1,
                    retry_at = NULL, last_error_code = NULL, updated_at = ?
                FROM picked WHERE ingestion.id = picked.id
                RETURNING ingestion.id, ingestion.object_key, ingestion.size_bytes,
                          ingestion.sha256, ingestion.skill_path, ingestion.task_id,
                          ingestion.validation_attempts
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, validationToken);
            statement.setTimestamp(4, Timestamp.from(expiresAt));
            statement.setTimestamp(5, Timestamp.from(now));
        }, result -> result.next() ? Optional.of(new ClaimedIngestion(
                result.getObject("id", UUID.class), validationToken,
                result.getString("object_key"), result.getLong("size_bytes"),
                result.getString("sha256"), result.getString("skill_path"),
                jdbc.queryForObject("SELECT source_id FROM github_sync_task WHERE id = ?",
                        Long.class, result.getObject("task_id", UUID.class)),
                result.getInt("validation_attempts"))) : Optional.empty());
    }

    @Override
    @Transactional
    public boolean validated(UUID ingestionId, UUID validationToken, String contentSha256,
                             int warningCount, String evidenceJson, Instant now) {
        lockTaskForIngestion(ingestionId);
        int updated = jdbc.update("""
                UPDATE github_sync_package_ingestion
                SET status = 'VALIDATED', validation_token = NULL, validation_expires_at = NULL,
                    validated_at = ?, content_sha256 = ?, validation_warning_count = ?,
                    license_evidence_json = ?::jsonb,
                    last_error_code = NULL, retry_at = NULL, updated_at = ?
                WHERE id = ? AND status = 'VALIDATING' AND validation_token = ?
                  AND validation_expires_at > ?
                """, Timestamp.from(now), contentSha256, warningCount, evidenceJson,
                Timestamp.from(now), ingestionId, validationToken, Timestamp.from(now));
        if (updated != 1) {
            return false;
        }
        jdbc.update("""
                UPDATE github_sync_path_result path
                SET outcome = 'ACCEPTED', reason = NULL, retry_at = NULL, updated_at = ?
                FROM github_sync_package_ingestion ingestion
                WHERE ingestion.id = ? AND path.task_id = ingestion.task_id
                  AND path.commit_sha = ingestion.commit_sha AND path.skill_path = ingestion.skill_path
                """, Timestamp.from(now), ingestionId);
        bindValidatedCandidate(ingestionId, now);
        jdbc.query("SELECT namespace_id, repository_id FROM github_sync_package_ingestion WHERE id = ?",
                result -> {
                    if (result.next()) {
                        outcomes.firstValid(result.getLong("namespace_id"), result.getLong("repository_id"), now);
                    }
                    return null;
                }, ingestionId);
        jdbc.update("""
                INSERT INTO github_official_validated_seed
                    (namespace_id, repository_id, github_owner_id, owner_login, ingestion_id, validated_at)
                SELECT ingestion.namespace_id, ingestion.repository_id,
                    (task.snapshot_json ->> 'ownerId')::bigint, task.snapshot_json ->> 'owner', ingestion.id, ?
                FROM github_sync_package_ingestion ingestion JOIN github_sync_task task ON task.id = ingestion.task_id
                JOIN github_deep_discovery_config config ON config.namespace_id = ingestion.namespace_id
                WHERE ingestion.id = ? AND (task.snapshot_json ->> 'ownerId')::bigint > 0
                ON CONFLICT (namespace_id, repository_id, github_owner_id) DO UPDATE
                    SET owner_login = EXCLUDED.owner_login
                """, Timestamp.from(now), ingestionId);
        jdbc.update("""
                UPDATE github_source_binding source SET github_owner_id = (task.snapshot_json ->> 'ownerId')::bigint
                FROM github_sync_package_ingestion ingestion JOIN github_sync_task task ON task.id = ingestion.task_id
                WHERE ingestion.id = ? AND source.namespace_id = ingestion.namespace_id
                    AND source.repository_id = ingestion.repository_id AND task.snapshot_json ->> 'ownerId' IS NOT NULL
                """, ingestionId);
        jdbc.query("""
                SELECT task.id, ingestion.commit_sha
                FROM github_sync_package_ingestion ingestion
                JOIN github_sync_task task ON task.id = ingestion.task_id
                WHERE ingestion.id = ?
                """, result -> {
            if (result.next()) {
                checkpoints.advanceIfComplete(result.getObject("id", UUID.class),
                        result.getString("commit_sha"), now);
            }
            return null;
        }, ingestionId);
        return true;
    }

    private void lockTaskForIngestion(UUID ingestionId) {
        // Repository pause/completion takes task before source/path locks. Validation must use the same
        // order so candidate-to-source promotion cannot deadlock an in-flight pause.
        jdbc.queryForList("""
                SELECT task.id FROM github_sync_task task
                JOIN github_sync_package_ingestion ingestion ON ingestion.task_id = task.id
                WHERE ingestion.id = ? FOR UPDATE OF task
                """, UUID.class, ingestionId);
    }

    /** Search hits become sources only after a real Skill package passes Java validation. */
    private void bindValidatedCandidate(UUID ingestionId, Instant now) {
        Long sourceId = jdbc.query("""
                INSERT INTO github_source_binding
                    (namespace_id, repository_id, owner_login, repository_name,
                     default_branch, visibility, last_observed_commit, next_check_at)
                SELECT candidate.namespace_id, candidate.repository_id,
                       candidate.owner_login, candidate.repository_name,
                       candidate.default_branch,
                       CASE WHEN namespace.type = 'CATALOG' THEN 'PUBLIC'
                            ELSE 'NAMESPACE_ONLY' END,
                       ingestion.commit_sha, ?
                FROM github_sync_package_ingestion ingestion
                JOIN github_discovery_candidate candidate
                  ON candidate.namespace_id = ingestion.namespace_id
                 AND candidate.repository_id = ingestion.repository_id
                JOIN namespace ON namespace.id = candidate.namespace_id
                WHERE ingestion.id = ? AND candidate.status IN ('PENDING', 'VALIDATING', 'BOUND', 'UNREACHABLE')
                ON CONFLICT (namespace_id, repository_id) DO UPDATE
                SET owner_login = EXCLUDED.owner_login,
                    repository_name = EXCLUDED.repository_name,
                    default_branch = COALESCE(EXCLUDED.default_branch,
                                              github_source_binding.default_branch),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, statement -> {
            Integer hours = schedulePolicies.get().repositoryHours();
            statement.setTimestamp(1, Timestamp.from(now.plus(
                    hours == null ? repositoryCheckInterval : Duration.ofHours(hours))));
            statement.setObject(2, ingestionId);
        }, result -> result.next() ? result.getLong("id") : null);
        if (sourceId == null) {
            return;
        }
        jdbc.update("""
                UPDATE github_discovery_candidate candidate
                SET status = 'BOUND', unreachable_attempts = 0,
                    last_error_code = NULL, last_seen_at = ?
                FROM github_sync_package_ingestion ingestion
                WHERE ingestion.id = ? AND candidate.namespace_id = ingestion.namespace_id
                  AND candidate.repository_id = ingestion.repository_id
                """, Timestamp.from(now), ingestionId);
        jdbc.update("""
                UPDATE github_sync_task task
                SET source_id = ?, updated_at = ?
                FROM github_sync_package_ingestion ingestion
                WHERE ingestion.id = ? AND task.id = ingestion.task_id AND task.source_id IS NULL
                """, sourceId, Timestamp.from(now), ingestionId);
        jdbc.update("""
                INSERT INTO github_source_origin (source_id, origin_type, origin_key)
                SELECT ?, origin.origin_type, origin.origin_key
                FROM github_discovery_candidate_origin origin
                JOIN github_discovery_candidate candidate ON candidate.id = origin.candidate_id
                JOIN github_sync_package_ingestion ingestion ON ingestion.id = ?
                WHERE candidate.namespace_id = ingestion.namespace_id
                  AND candidate.repository_id = ingestion.repository_id
                ON CONFLICT (source_id, origin_type, origin_key) DO NOTHING
                """, sourceId, ingestionId);
    }

    @Override
    @Transactional
    public boolean failed(UUID ingestionId, UUID validationToken, String reason,
                          Instant retryAt, String pathOutcome, Instant now) {
        if (pathOutcome != null && !pathOutcome.equals("RETRYABLE_FAILURE")
                && !pathOutcome.equals("TERMINAL_FAILURE") && !pathOutcome.equals("IGNORED")) {
            throw new IllegalArgumentException("invalid GitHub sync path failure outcome");
        }
        lockTaskForIngestion(ingestionId);
        String status = retryAt == null ? "FAILED" : "RETRY_WAIT";
        int updated = jdbc.update("""
                UPDATE github_sync_package_ingestion
                SET status = ?, validation_token = NULL, validation_expires_at = NULL,
                    last_error_code = ?, retry_at = ?, updated_at = ?
                WHERE id = ? AND status = 'VALIDATING' AND validation_token = ?
                  AND validation_expires_at > ?
                """, status, reason, retryAt == null ? null : Timestamp.from(retryAt),
                Timestamp.from(now), ingestionId, validationToken, Timestamp.from(now));
        if (updated != 1) {
            return false;
        }
        if (pathOutcome != null) {
            jdbc.update("""
                    UPDATE github_sync_path_result path
                    SET outcome = ?, reason = ?, retry_at = ?, updated_at = ?
                    FROM github_sync_package_ingestion ingestion
                    WHERE ingestion.id = ? AND path.task_id = ingestion.task_id
                      AND path.commit_sha = ingestion.commit_sha AND path.skill_path = ingestion.skill_path
                    """, pathOutcome, reason, retryAt == null ? null : Timestamp.from(retryAt),
                    Timestamp.from(now), ingestionId);
            jdbc.update("""
                    UPDATE github_source_binding source
                    SET last_fully_processed_commit = NULL, updated_at = ?
                    FROM github_sync_task task, github_sync_package_ingestion ingestion
                    WHERE ingestion.id = ? AND task.id = ingestion.task_id AND source.id = task.source_id
                      AND source.last_fully_processed_commit = ingestion.commit_sha
                    """, Timestamp.from(now), ingestionId);
        }
        return true;
    }

    @Override
    @Transactional
    public List<CleanupIngestion> claimCleanup(Instant now, Duration signedUrlGrace, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("cleanup batch limit must be between 1 and 100");
        }
        return jdbc.query("""
                WITH picked AS (
                    SELECT ingestion.id FROM github_sync_package_ingestion ingestion
                    JOIN github_sync_staged_upload upload ON upload.id = ingestion.upload_id
                    WHERE ingestion.status IN ('FAILED', 'NO_CHANGE', 'READY_FOR_REVIEW',
                                               'APPROVED', 'REJECTED', 'SUPERSEDED')
                      AND ingestion.object_cleaned_at IS NULL
                      AND (ingestion.object_cleanup_claimed_at IS NULL
                           OR ingestion.object_cleanup_claimed_at < ?)
                      AND (upload.mode = 'LOCAL_STREAM' OR upload.expires_at < ?)
                    ORDER BY ingestion.updated_at, ingestion.id
                    LIMIT ? FOR UPDATE OF ingestion SKIP LOCKED
                )
                UPDATE github_sync_package_ingestion ingestion
                SET object_cleanup_claimed_at = ?
                FROM picked WHERE ingestion.id = picked.id
                RETURNING ingestion.id, ingestion.object_key
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofMinutes(15))));
            statement.setTimestamp(2, Timestamp.from(now.minus(signedUrlGrace)));
            statement.setInt(3, limit);
            statement.setTimestamp(4, Timestamp.from(now));
        }, (result, row) -> new CleanupIngestion(result.getObject("id", UUID.class),
                result.getString("object_key")));
    }

    @Override
    public void markCleaned(UUID ingestionId, Instant now) {
        jdbc.update("""
                UPDATE github_sync_package_ingestion
                SET object_cleaned_at = ?, object_cleanup_claimed_at = NULL
                WHERE id = ? AND status IN ('FAILED', 'NO_CHANGE', 'READY_FOR_REVIEW',
                                            'APPROVED', 'REJECTED', 'SUPERSEDED')
                  AND object_cleaned_at IS NULL
                """, Timestamp.from(now), ingestionId);
    }
}
