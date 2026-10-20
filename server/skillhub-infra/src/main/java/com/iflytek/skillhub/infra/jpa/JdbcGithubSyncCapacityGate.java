package com.iflytek.skillhub.infra.jpa;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Shared unresolved-coordinate count for task admission and pre-acquisition scheduling. */
@Component
public class JdbcGithubSyncCapacityGate {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncCapacityGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean allowsFullAcquisition(long namespaceId, Instant now) {
        Policy policy = jdbc.query("""
                SELECT global_high, global_low, namespace_high, namespace_low, global_paused
                FROM github_sync_admission_policy WHERE id = TRUE
                """, result -> result.next() ? new Policy(
                (Integer) result.getObject("global_high"),
                (Integer) result.getObject("global_low"),
                (Integer) result.getObject("namespace_high"),
                (Integer) result.getObject("namespace_low"),
                result.getBoolean("global_paused")) : null);
        if (policy == null || policy.globalHigh() == null || policy.namespaceHigh() == null) {
            return false;
        }
        Boolean namespacePaused = jdbc.query("""
                SELECT paused FROM github_sync_namespace_admission_state WHERE namespace_id = ?
                """, result -> result.next() ? result.getBoolean("paused") : false, namespaceId);
        return belowResumeBoundary(activeCount(null, now), policy.globalHigh(), policy.globalLow(),
                    policy.globalPaused())
                && belowResumeBoundary(activeCount(namespaceId, now),
                    policy.namespaceHigh(), policy.namespaceLow(), Boolean.TRUE.equals(namespacePaused));
    }

    public int activeCount(Long namespaceId, Instant now) {
        String sql = """
                SELECT count(*) FROM (
                    SELECT admission.namespace_id, admission.repository_id, admission.skill_path
                    FROM github_sync_package_admission admission
                    JOIN github_sync_task task ON task.id = admission.task_id
                    WHERE admission.status = 'ACTIVE' AND task.status = 'LEASED'
                      AND task.lease_generation = admission.lease_generation
                      AND task.lease_expires_at > ?
                    UNION
                    SELECT ingestion.namespace_id, ingestion.repository_id, ingestion.skill_path
                    FROM github_sync_package_ingestion ingestion
                    WHERE ingestion.status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT',
                                               'VALIDATED', 'READY_FOR_REVIEW', 'BLOCKED')
                ) active_coordinates
                """ + (namespaceId == null ? "" : " WHERE active_coordinates.namespace_id = ?");
        Integer count = namespaceId == null
                ? jdbc.queryForObject(sql, Integer.class, Timestamp.from(now))
                : jdbc.queryForObject(sql, Integer.class, Timestamp.from(now), namespaceId);
        return count == null ? 0 : count;
    }

    /** Pending reviews and conflicts do not prevent checking for a newer upstream revision. */
    public boolean hasUnfinishedIngestion(long namespaceId, long repositoryId) {
        Boolean unfinished = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM github_sync_package_ingestion
                    WHERE namespace_id = ? AND repository_id = ?
                      AND status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT', 'VALIDATED'))
                """, Boolean.class, namespaceId, repositoryId);
        return Boolean.TRUE.equals(unfinished);
    }

    private boolean belowResumeBoundary(int count, int high, int low, boolean paused) {
        return paused ? count <= low : count < high;
    }

    private record Policy(Integer globalHigh, Integer globalLow,
                          Integer namespaceHigh, Integer namespaceLow, boolean globalPaused) {}
}
