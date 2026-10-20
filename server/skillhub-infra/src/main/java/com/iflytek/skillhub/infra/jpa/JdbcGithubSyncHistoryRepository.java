package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSyncHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGithubSyncHistoryRepository implements GithubSyncHistoryRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CleanupResult deleteExpiredDiscoveryHistory(Instant cutoff, int batchSize) {
        requireBatchSize(batchSize);
        // Exclude scheduler GAP-task reuse while cleanup locks tasks and clears ledger FKs.
        jdbc.query("SELECT id FROM github_deep_discovery_config WHERE id = TRUE FOR SHARE", result -> {});
        // Retired queries never schedule another root; expire their completed diagnostic trees too.
        jdbc.update("""
                WITH picked AS (
                    SELECT id FROM github_official_discovery_shard
                    WHERE parent_id IS NULL AND state = 'COMPLETE' AND completed_at < ?
                    ORDER BY completed_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM github_official_discovery_shard shard USING picked WHERE shard.id = picked.id
                """, Timestamp.from(cutoff), batchSize);
        var deletedTasks = jdbc.query("""
                WITH picked AS (
                    SELECT task.id FROM github_sync_task task
                    JOIN github_sync_run run ON run.id = task.run_id
                    WHERE task.kind = 'DISCOVERY'
                      AND NOT EXISTS (SELECT 1 FROM github_platform_collection_control platform
                        WHERE platform.namespace_id=task.namespace_id AND platform.paused
                          AND platform.version=task.platform_pause_version AND task.platform_stop_ack_at IS NULL)
                      AND NOT EXISTS (SELECT 1 FROM github_deep_discovery_query progress
                                      WHERE progress.active_task_id = task.id)
                      AND task.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                      AND task.completed_at < ?
                      AND run.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                      AND run.finished_at < ?
                      AND NOT EXISTS (SELECT 1 FROM github_sync_package_ingestion ingestion
                                      WHERE ingestion.task_id = task.id)
                      AND NOT EXISTS (SELECT 1 FROM github_sync_package_admission admission
                                      WHERE admission.task_id = task.id)
                      AND NOT EXISTS (SELECT 1 FROM github_sync_path_result path
                                      WHERE path.task_id = task.id)
                      AND NOT EXISTS (SELECT 1 FROM github_discovery_candidate candidate
                                      WHERE candidate.validation_task_id = task.id)
                    ORDER BY task.completed_at, task.id
                    LIMIT ? FOR UPDATE OF task SKIP LOCKED
                )
                DELETE FROM github_sync_task task USING picked
                WHERE task.id = picked.id RETURNING task.id
                """, (result, row) -> result.getObject("id"),
                Timestamp.from(cutoff), Timestamp.from(cutoff), batchSize).size();
        var deletedRuns = jdbc.query("""
                WITH picked AS (
                    SELECT run.id FROM github_sync_run run
                    WHERE run.kind = 'DISCOVERY'
                      AND run.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                      AND run.finished_at < ?
                      AND NOT EXISTS (SELECT 1 FROM github_sync_task task WHERE task.run_id = run.id)
                    ORDER BY run.finished_at, run.id
                    LIMIT ? FOR UPDATE OF run SKIP LOCKED
                )
                DELETE FROM github_sync_run run USING picked
                WHERE run.id = picked.id RETURNING run.id
                """, (result, row) -> result.getObject("id"),
                Timestamp.from(cutoff), batchSize).size();
        return new CleanupResult(deletedTasks, deletedRuns);
    }

    @Override
    @Transactional
    public CleanupResult purgeExpiredRepositoryDetails(Instant cutoff, Instant now, int batchSize) {
        requireBatchSize(batchSize);
        List<UUID> taskIds = jdbc.query("""
                SELECT task.id FROM github_sync_task task
                JOIN github_sync_run run ON run.id = task.run_id
                WHERE task.kind = 'REPOSITORY_SYNC'
                  AND task.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                  AND task.completed_at < ? AND task.details_purged_at IS NULL
                  AND run.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                  AND run.finished_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM github_sync_package_ingestion ingestion
                      WHERE ingestion.task_id = task.id
                        AND ingestion.status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT',
                                                 'VALIDATED', 'READY_FOR_REVIEW', 'BLOCKED'))
                  AND NOT EXISTS (
                      SELECT 1 FROM github_discovery_candidate candidate
                      WHERE candidate.validation_task_id = task.id
                        AND candidate.status IN ('VALIDATING', 'UNREACHABLE'))
                ORDER BY task.completed_at, task.id
                LIMIT ? FOR UPDATE OF task SKIP LOCKED
                """, (result, row) -> result.getObject("id", UUID.class),
                Timestamp.from(cutoff), Timestamp.from(cutoff), batchSize);
        if (!taskIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
            Object[] ids = taskIds.toArray();
            jdbc.update("""
                    DELETE FROM github_sync_path_result path
                    USING github_sync_task task
                    WHERE path.task_id = task.id AND task.id IN (%s)
                      AND (task.source_id IS NULL OR EXISTS (
                          SELECT 1 FROM github_sync_path_result newer_path
                          JOIN github_sync_task newer_task ON newer_task.id = newer_path.task_id
                          WHERE newer_task.source_id = task.source_id
                            AND newer_path.skill_path = path.skill_path
                            AND (newer_task.created_at, newer_task.id,
                                 newer_path.updated_at, newer_path.id)
                              > (task.created_at, task.id, path.updated_at, path.id)))
                    """.formatted(placeholders), ids);
            Object[] updateArgs = new Object[taskIds.size() + 1];
            updateArgs[0] = Timestamp.from(now);
            System.arraycopy(ids, 0, updateArgs, 1, ids.length);
            jdbc.update("""
                    UPDATE github_sync_task
                    SET snapshot_json = jsonb_strip_nulls(jsonb_build_object(
                            'repositoryId', snapshot_json -> 'repositoryId',
                            'owner', snapshot_json -> 'owner',
                            'name', snapshot_json -> 'name')),
                        path_checkpoint = NULL, discovery_checkpoint = NULL,
                        incomplete_subtrees = '[]'::jsonb, last_error_detail = NULL,
                        credential_id = NULL, credential_fingerprint = NULL,
                        details_purged_at = ?
                    WHERE id IN (%s)
                    """.formatted(placeholders), updateArgs);
        }
        int runs = jdbc.update("""
                WITH picked AS (
                    SELECT run.id FROM github_sync_run run
                    WHERE run.kind = 'REPOSITORY_SYNC'
                      AND run.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                      AND run.finished_at < ? AND run.details_purged_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM github_sync_task task
                                      WHERE task.run_id = run.id AND task.details_purged_at IS NULL)
                    ORDER BY run.finished_at, run.id
                    LIMIT ? FOR UPDATE OF run SKIP LOCKED
                )
                UPDATE github_sync_run run
                SET details_purged_at = ?
                FROM picked WHERE run.id = picked.id
                """, Timestamp.from(cutoff), batchSize, Timestamp.from(now));
        return new CleanupResult(taskIds.size(), runs);
    }

    private void requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("GitHub history cleanup batch must be 1 to 1000");
        }
    }
}
