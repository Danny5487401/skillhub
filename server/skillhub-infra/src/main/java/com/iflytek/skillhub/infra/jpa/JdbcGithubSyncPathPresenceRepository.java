package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSkillPathPolicy;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reconciles source path presence only from a complete, current-rule repository snapshot. */
@Repository
public class JdbcGithubSyncPathPresenceRepository {
    private final JdbcTemplate jdbc;
    private final JdbcGithubSourceIgnoreRepository ignores;

    public JdbcGithubSyncPathPresenceRepository(JdbcTemplate jdbc, JdbcGithubSourceIgnoreRepository ignores) {
        this.jdbc = jdbc;
        this.ignores = ignores;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(long sourceId, UUID taskId, String commitSha, Instant now) {
        Snapshot snapshot = jdbc.query("""
                SELECT source.namespace_id, source.status,
                       COALESCE(task.snapshot_json -> 'ignorePatterns', '[]'::jsonb)
                           = source.ignore_patterns AS rules_match
                FROM github_source_binding source
                JOIN github_sync_task task ON task.source_id = source.id
                WHERE source.id = ? AND task.id = ? AND task.kind = 'REPOSITORY_SYNC'
                  AND task.mode = 'FULL' AND task.status = 'ACQUISITION_COMPLETE'
                  AND task.fixed_commit_sha = ?
                  AND NOT EXISTS (SELECT 1 FROM github_sync_task newer
                      WHERE newer.source_id = task.source_id
                        AND newer.kind = 'REPOSITORY_SYNC' AND newer.mode = 'FULL'
                        AND (newer.created_at, newer.id) > (task.created_at, task.id))
                """, result -> result.next() ? new Snapshot(result.getLong("namespace_id"),
                result.getString("status"), result.getBoolean("rules_match")) : null,
                sourceId, taskId, commitSha);
        if (snapshot == null || "PAUSED".equals(snapshot.status()) || !snapshot.rulesMatch()) {
            return;
        }
        List<String> patterns = ignores.find(snapshot.namespaceId(), sourceId)
                .orElseThrow(() -> new IllegalStateException("GitHub source disappeared during path reconciliation"));
        jdbc.update("""
                UPDATE github_source_skill_binding binding
                SET upstream_presence = 'PRESENT', presence_commit_sha = ?,
                    missing_since = NULL, updated_at = ?
                WHERE binding.source_id = ? AND EXISTS (
                    SELECT 1 FROM github_sync_path_result path
                    WHERE path.task_id = ? AND path.commit_sha = ?
                      AND path.skill_path = binding.skill_path)
                """, commitSha, Timestamp.from(now), sourceId, taskId, commitSha);
        List<PathBinding> absent = jdbc.query("""
                SELECT binding.id, binding.skill_path
                FROM github_source_skill_binding binding
                WHERE binding.source_id = ? AND NOT EXISTS (
                    SELECT 1 FROM github_sync_path_result path
                    WHERE path.task_id = ? AND path.commit_sha = ?
                      AND path.skill_path = binding.skill_path)
                """, (result, row) -> new PathBinding(result.getLong("id"),
                result.getString("skill_path")), sourceId, taskId, commitSha);
        List<PathBinding> missing = absent.stream()
                .filter(binding -> !GithubSkillPathPolicy.ignored(binding.skillPath(), patterns))
                .toList();
        jdbc.batchUpdate("""
                UPDATE github_source_skill_binding
                SET upstream_presence = 'MISSING', presence_commit_sha = ?,
                    missing_since = COALESCE(missing_since, ?), updated_at = ?
                WHERE id = ? AND source_id = ?
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setString(1, commitSha);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setLong(4, missing.get(index).id());
                statement.setLong(5, sourceId);
            }

            @Override
            public int getBatchSize() {
                return missing.size();
            }
        });
    }

    private record Snapshot(long namespaceId, String status, boolean rulesMatch) {}
    private record PathBinding(long id, String skillPath) {}
}
