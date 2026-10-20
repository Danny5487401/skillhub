package com.iflytek.skillhub.infra.jpa;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Only complete, validated acquisition may advance a source's full-processing marker. */
@Repository
public class JdbcGithubSyncSourceCheckpointRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncSourceCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void advanceIfComplete(UUID taskId, String commitSha, Instant now) {
        jdbc.update("""
                UPDATE github_source_binding source
                SET last_fully_processed_commit = ?, updated_at = ?
                FROM github_sync_task task
                WHERE task.id = ? AND task.source_id = source.id
                  AND task.kind = 'REPOSITORY_SYNC' AND task.mode = 'FULL'
                  AND task.status = 'ACQUISITION_COMPLETE' AND task.fixed_commit_sha = ?
                  AND source.last_observed_commit = ?
                  AND COALESCE(task.snapshot_json -> 'ignorePatterns', '[]'::jsonb)
                      = source.ignore_patterns
                  AND NOT EXISTS (
                      SELECT 1 FROM github_sync_package_ingestion ingestion
                      WHERE ingestion.task_id = task.id
                        AND ingestion.status NOT IN ('VALIDATED', 'NO_CHANGE',
                                                     'READY_FOR_REVIEW', 'APPROVED',
                                                     'REJECTED', 'SUPERSEDED', 'BLOCKED'))
                  AND NOT EXISTS (
                      SELECT 1 FROM github_sync_path_result path
                      WHERE path.task_id = task.id AND path.commit_sha = ?
                        AND path.outcome IN ('RETRYABLE_FAILURE', 'TERMINAL_FAILURE'))
                """, commitSha, Timestamp.from(now), taskId, commitSha, commitSha, commitSha);
    }
}
