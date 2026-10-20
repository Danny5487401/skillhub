package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.dto.GithubSyncRunSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncRunDetailResponse;
import com.iflytek.skillhub.dto.GithubSyncRunTaskResponse;
import com.iflytek.skillhub.dto.GithubSyncRunPathResponse;
import com.iflytek.skillhub.dto.GithubSyncSourceSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSkillPathResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.RunKind;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.RunStatus;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Target;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.githubsync.GithubSkillPathPolicy;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import com.iflytek.skillhub.infra.jpa.JdbcGithubSyncCapacityGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pages source and run rows before joining their current acquisition and ingestion summaries. */
@Repository
public class GithubSyncManagementQueryRepository {
    private final JdbcTemplate jdbc;
    private final GithubSourceIgnoreRepository ignores;
    private final JdbcGithubSyncCapacityGate capacityGate;

    public GithubSyncManagementQueryRepository(JdbcTemplate jdbc, GithubSourceIgnoreRepository ignores,
                                               JdbcGithubSyncCapacityGate capacityGate) {
        this.jdbc = jdbc;
        this.ignores = ignores;
        this.capacityGate = capacityGate;
    }

    public long platformNamespaceId() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT id FROM namespace WHERE slug = 'github' AND type = 'CATALOG'
                """, Long.class), "@github catalog namespace is missing");
    }

    @Transactional(readOnly = true)
    public AdmissionSnapshot admissionSnapshot() {
        long platformNamespaceId = platformNamespaceId();
        Instant now = Instant.now();
        Integer pendingCandidates = jdbc.queryForObject("""
                SELECT count(*) FROM github_discovery_candidate
                WHERE namespace_id = ? AND status IN ('PENDING', 'VALIDATING')
                """, Integer.class, platformNamespaceId);
        Boolean platformPaused = jdbc.query("""
                SELECT paused FROM github_sync_namespace_admission_state WHERE namespace_id = ?
                """, result -> result.next() && result.getBoolean("paused"), platformNamespaceId);
        return new AdmissionSnapshot(capacityGate.activeCount(null, now),
                capacityGate.activeCount(platformNamespaceId, now),
                pendingCandidates == null ? 0 : pendingCandidates, Boolean.TRUE.equals(platformPaused));
    }

    public record AdmissionSnapshot(int globalUnresolvedCount, int platformUnresolvedCount,
                                    int platformPendingCandidateCount, boolean platformPaused) {}

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncSourceSummaryResponse> sources(long namespaceId, int page, int size) {
        validatePage(page, size);
        List<GithubSyncSourceSummaryResponse> items = jdbc.query("""
                WITH selected AS (
                    SELECT source.* FROM github_source_binding source
                    WHERE source.namespace_id = ?
                    ORDER BY source.id DESC LIMIT ? OFFSET ?
                )
                SELECT source.id, source.repository_id, source.owner_login, source.repository_name,
                       source.default_branch, source.visibility, source.status,
                       source.last_observed_commit, source.last_fully_processed_commit,
                       source.next_check_at, source.last_error_code,
                       task.id AS task_id, task.status AS task_status, task.mode AS task_mode,
                       task.last_error_code AS task_error_code,
                       task.last_error_detail AS task_error_detail, task.retry_at AS task_retry_at,
                       COALESCE(packages.received, 0) AS received_packages,
                       COALESCE(packages.processing, 0) AS processing_packages,
                       COALESCE(packages.pending_review, 0) AS pending_review_packages,
                       COALESCE(packages.blocked, 0) AS blocked_packages,
                       COALESCE(packages.failed, 0) AS failed_packages,
                       COALESCE(tracked.skills, 0) AS tracked_skills
                FROM selected source
                LEFT JOIN LATERAL (
                    SELECT task.id, task.status, task.mode, task.last_error_code,
                           task.last_error_detail, task.retry_at
                    FROM github_sync_task task WHERE task.source_id = source.id
                    ORDER BY task.created_at DESC, task.id DESC LIMIT 1
                ) task ON TRUE
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE latest.status = 'RECEIVED') AS received,
                           count(*) FILTER (WHERE latest.status IN
                               ('VALIDATING', 'RETRY_WAIT', 'VALIDATED')) AS processing,
                           count(*) FILTER (WHERE latest.status = 'READY_FOR_REVIEW') AS pending_review,
                           count(*) FILTER (WHERE latest.status = 'BLOCKED') AS blocked,
                           count(*) FILTER (WHERE latest.status = 'FAILED') AS failed
                    FROM (
                        SELECT DISTINCT ON (ingestion.skill_path)
                               ingestion.skill_path, ingestion.status
                        FROM github_sync_package_ingestion ingestion
                        WHERE ingestion.namespace_id = source.namespace_id
                          AND ingestion.repository_id = source.repository_id
                        ORDER BY ingestion.skill_path, ingestion.created_at DESC, ingestion.id DESC
                    ) latest
                ) packages ON TRUE
                LEFT JOIN LATERAL (
                    SELECT count(*) AS skills FROM github_source_skill_binding binding
                    WHERE binding.source_id = source.id
                ) tracked ON TRUE
                ORDER BY source.id DESC
                """, (result, row) -> mapSource(result), namespaceId, size, (long) page * size);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM github_source_binding WHERE namespace_id = ?
                """, Long.class, namespaceId);
        return new PageResponse<>(items, total == null ? 0 : total, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncRunSummaryResponse> runs(long namespaceId, int page, int size) {
        return runs(namespaceId, page, size, null, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncRunSummaryResponse> runs(long namespaceId, int page, int size, RunKind kind, RunStatus status) {
        validatePage(page, size);
        List<GithubSyncRunSummaryResponse> items = jdbc.query("""
                WITH selected AS (
                    SELECT run.* FROM github_sync_run run WHERE run.namespace_id = ?
                      AND (CAST(? AS text) IS NULL OR run.kind=?)
                      AND (CAST(? AS text) IS NULL OR run.status=?)
                      AND run.details_purged_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM github_sync_task preview
                          WHERE preview.run_id = run.id
                            AND preview.snapshot_json ->> 'entryType' = 'SEARCH_PREVIEW')
                    ORDER BY run.created_at DESC, run.id DESC LIMIT ? OFFSET ?
                )
                SELECT run.id, run.kind, run.status, run.created_at, run.finished_at,
                       (SELECT count(*) FROM github_sync_task task WHERE task.run_id = run.id)
                           AS task_count,
                       (SELECT min(task.retry_at) FROM github_sync_task task
                        WHERE task.run_id = run.id AND task.status = 'RETRY_WAIT')
                           AS next_retry_at,
                       COALESCE(packages.received, 0) AS received_packages,
                       COALESCE(packages.processing, 0) AS processing_packages,
                       COALESCE(packages.pending_review, 0) AS pending_review_packages,
                       COALESCE(packages.blocked, 0) AS blocked_packages,
                       COALESCE(packages.failed, 0) AS failed_packages,
                       latest_error.error_code AS last_error_code,
                       targets.target_count, targets.target_type, targets.target_value
                FROM selected run
                LEFT JOIN LATERAL (
                    SELECT count(*) target_count,
                           CASE WHEN count(*)=1 THEN min(type) END target_type,
                           CASE WHEN count(*)=1 THEN min(value) END target_value
                    FROM (
                        SELECT DISTINCT ON (type,COALESCE(repository_key,lower(value))) type,value
                        FROM (
                        SELECT task.created_at,task.id,
                          COALESCE(source.repository_id::text,task.snapshot_json->>'repositoryId') repository_key,
                          CASE WHEN source.id IS NOT NULL OR task.kind='REPOSITORY_SYNC'
                               OR task.snapshot_json->>'entryType'='CURATED_REPOSITORY' THEN 'REPOSITORY'
                               WHEN task.snapshot_json->>'entryType'='CURATED_OWNER' THEN 'OWNER'
                               WHEN task.official_query_id IS NOT NULL OR task.snapshot_json->>'ruleId' IS NOT NULL THEN 'QUERY'
                          END type,
                          CASE WHEN source.id IS NOT NULL THEN source.owner_login||'/'||source.repository_name
                               WHEN task.kind='REPOSITORY_SYNC' THEN (task.snapshot_json->>'owner')||'/'||(task.snapshot_json->>'name')
                               WHEN task.snapshot_json->>'entryType'='CURATED_REPOSITORY' THEN
                                   (task.snapshot_json->>'owner')||'/'||(task.snapshot_json->>'repositoryName')
                               WHEN task.snapshot_json->>'entryType'='CURATED_OWNER' THEN task.snapshot_json->>'owner'
                               WHEN task.official_query_id IS NOT NULL THEN task.official_query_id::text
                               ELSE task.snapshot_json->>'ruleId' END value
                        FROM github_sync_task task LEFT JOIN github_source_binding source ON source.id=task.source_id
                            AND source.namespace_id=task.namespace_id
                        WHERE task.run_id=run.id AND task.namespace_id=run.namespace_id
                        ) described WHERE type IS NOT NULL AND value IS NOT NULL
                        ORDER BY type,COALESCE(repository_key,lower(value)),created_at DESC,id DESC
                    ) distinct_targets
                ) targets ON TRUE
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE ingestion.status = 'RECEIVED') AS received,
                           count(*) FILTER (WHERE ingestion.status IN
                               ('VALIDATING', 'RETRY_WAIT', 'VALIDATED')) AS processing,
                           count(*) FILTER (WHERE ingestion.status = 'READY_FOR_REVIEW') AS pending_review,
                           count(*) FILTER (WHERE ingestion.status = 'BLOCKED') AS blocked,
                           count(*) FILTER (WHERE ingestion.status = 'FAILED') AS failed
                    FROM github_sync_task task
                    JOIN github_sync_package_ingestion ingestion ON ingestion.task_id = task.id
                    WHERE task.run_id = run.id
                ) packages ON TRUE
                LEFT JOIN LATERAL (
                    SELECT errors.error_code FROM (
                        SELECT task.last_error_code AS error_code, task.updated_at
                        FROM github_sync_task task
                        WHERE task.run_id = run.id
                          AND task.status IN ('RETRY_WAIT', 'PARTIAL', 'FAILED')
                          AND task.last_error_code IS NOT NULL
                        UNION ALL
                        SELECT ingestion.last_error_code AS error_code, ingestion.updated_at
                        FROM github_sync_task task
                        JOIN github_sync_package_ingestion ingestion ON ingestion.task_id = task.id
                        WHERE task.run_id = run.id
                          AND ingestion.status IN ('RETRY_WAIT', 'BLOCKED', 'FAILED')
                          AND ingestion.last_error_code IS NOT NULL
                    ) errors
                    ORDER BY errors.updated_at DESC LIMIT 1
                ) latest_error ON TRUE
                ORDER BY run.created_at DESC, run.id DESC
                """, (result, row) -> mapRun(result), namespaceId, kind == null ? null : kind.name(), kind == null ? null : kind.name(),
                status == null ? null : status.name(), status == null ? null : status.name(), size, (long) page * size);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM github_sync_run run WHERE run.namespace_id = ?
                      AND (CAST(? AS text) IS NULL OR run.kind=?)
                      AND (CAST(? AS text) IS NULL OR run.status=?)
                  AND run.details_purged_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM github_sync_task preview
                      WHERE preview.run_id = run.id
                        AND preview.snapshot_json ->> 'entryType' = 'SEARCH_PREVIEW')
                """, Long.class, namespaceId, kind == null ? null : kind.name(), kind == null ? null : kind.name(),
                status == null ? null : status.name(), status == null ? null : status.name());
        return new PageResponse<>(items, total == null ? 0 : total, page, size);
    }

    @Transactional(readOnly = true)
    public GithubSyncRunDetailResponse runDetail(long namespaceId, UUID runId) {
        return jdbc.query("""
                SELECT run.id, run.kind, run.status, run.created_at, run.finished_at,
                       (SELECT count(*) FROM github_sync_task task WHERE task.run_id = run.id) AS task_count,
                       (SELECT count(*) FROM github_sync_task task
                        JOIN github_sync_path_result path ON path.task_id = task.id
                        WHERE task.run_id = run.id) AS path_count,
                       (SELECT min(task.retry_at) FROM github_sync_task task
                        WHERE task.run_id = run.id AND task.status = 'RETRY_WAIT') AS next_retry_at,
                       COALESCE(packages.received, 0) AS received_packages,
                       COALESCE(packages.processing, 0) AS processing_packages,
                       COALESCE(packages.pending_review, 0) AS pending_review_packages,
                       COALESCE(packages.blocked, 0) AS blocked_packages,
                       COALESCE(packages.failed, 0) AS failed_packages,
                       (SELECT task.last_error_code FROM github_sync_task task
                        WHERE task.run_id = run.id AND task.last_error_code IS NOT NULL
                        ORDER BY task.updated_at DESC, task.id DESC LIMIT 1) AS last_error_code
                FROM github_sync_run run
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE ingestion.status = 'RECEIVED') AS received,
                           count(*) FILTER (WHERE ingestion.status IN
                               ('VALIDATING', 'RETRY_WAIT', 'VALIDATED')) AS processing,
                           count(*) FILTER (WHERE ingestion.status = 'READY_FOR_REVIEW') AS pending_review,
                           count(*) FILTER (WHERE ingestion.status = 'BLOCKED') AS blocked,
                           count(*) FILTER (WHERE ingestion.status = 'FAILED') AS failed
                    FROM github_sync_task task
                    JOIN github_sync_package_ingestion ingestion ON ingestion.task_id = task.id
                    WHERE task.run_id = run.id
                ) packages ON TRUE
                WHERE run.id = ? AND run.namespace_id = ? AND run.details_purged_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM github_sync_task preview
                      WHERE preview.run_id = run.id
                        AND preview.snapshot_json ->> 'entryType' = 'SEARCH_PREVIEW')
                """, result -> {
            if (!result.next()) {
                throw new DomainNotFoundException("error.githubSync.run.notFound");
            }
            return new GithubSyncRunDetailResponse(result.getObject("id", UUID.class),
                    result.getString("kind"), result.getString("status"),
                    instant(result, "created_at"), instant(result, "finished_at"),
                    result.getLong("task_count"), result.getLong("path_count"),
                    instant(result, "next_retry_at"), result.getLong("received_packages"),
                    result.getLong("processing_packages"), result.getLong("pending_review_packages"),
                    result.getLong("blocked_packages"), result.getLong("failed_packages"),
                    result.getString("last_error_code"));
        }, runId, namespaceId);
    }

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncRunTaskResponse> runTasks(
            long namespaceId, UUID runId, int page, int size) {
        validatePage(page, size);
        GithubSyncRunDetailResponse run = runDetail(namespaceId, runId);
        List<GithubSyncRunTaskResponse> items = jdbc.query("""
                SELECT task.id, task.source_id, source.owner_login, source.repository_name,
                       task.kind, task.mode, task.status, task.fixed_commit_sha,
                       task.last_error_code, task.retry_at, task.created_at, task.completed_at
                FROM github_sync_task task
                LEFT JOIN github_source_binding source ON source.id = task.source_id
                WHERE task.run_id = ? AND task.namespace_id = ?
                ORDER BY task.created_at, task.id LIMIT ? OFFSET ?
                """, (result, row) -> new GithubSyncRunTaskResponse(
                result.getObject("id", UUID.class), result.getObject("source_id", Long.class),
                repository(result), result.getString("kind"), result.getString("mode"),
                result.getString("status"), result.getString("fixed_commit_sha"),
                result.getString("last_error_code"), instant(result, "retry_at"),
                instant(result, "created_at"), instant(result, "completed_at")),
                runId, namespaceId, size, (long) page * size);
        return new PageResponse<>(items, run.taskCount(), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncRunPathResponse> runPaths(
            long namespaceId, UUID runId, int page, int size) {
        validatePage(page, size);
        GithubSyncRunDetailResponse run = runDetail(namespaceId, runId);
        List<GithubSyncRunPathResponse> items = jdbc.query("""
                SELECT task.id AS task_id, task.source_id, source.owner_login, source.repository_name,
                       path.skill_path, path.commit_sha, path.outcome, path.reason,
                       path.retry_at, path.updated_at, ingestion.status AS ingestion_status,
                       ingestion.last_error_code AS ingestion_error_code,
                       review.status AS review_status
                FROM github_sync_task task
                JOIN github_sync_path_result path ON path.task_id = task.id
                LEFT JOIN github_source_binding source ON source.id = task.source_id
                LEFT JOIN github_sync_package_ingestion ingestion ON ingestion.id = path.ingestion_id
                LEFT JOIN LATERAL (
                    SELECT item.status FROM review_task item
                    WHERE item.github_sync_ingestion_id = ingestion.id
                    ORDER BY item.id DESC LIMIT 1
                ) review ON TRUE
                WHERE task.run_id = ? AND task.namespace_id = ?
                ORDER BY path.updated_at DESC, path.id DESC LIMIT ? OFFSET ?
                """, (result, row) -> new GithubSyncRunPathResponse(
                result.getObject("task_id", UUID.class), result.getObject("source_id", Long.class),
                repository(result), result.getString("skill_path"), result.getString("commit_sha"),
                result.getString("outcome"), result.getString("reason"),
                result.getString("ingestion_status"), result.getString("ingestion_error_code"),
                result.getString("review_status"),
                instant(result, "retry_at"), instant(result, "updated_at")),
                runId, namespaceId, size, (long) page * size);
        return new PageResponse<>(items, run.pathCount(), page, size);
    }

    private String repository(ResultSet result) throws SQLException {
        String owner = result.getString("owner_login");
        String name = result.getString("repository_name");
        return owner == null || name == null ? null : owner + "/" + name;
    }

    @Transactional(readOnly = true)
    public PageResponse<GithubSyncSkillPathResponse> skillPaths(
            long namespaceId, long sourceId, int page, int size) {
        validatePage(page, size);
        List<String> ignorePatterns = ignores.find(namespaceId, sourceId)
                .orElseThrow(() -> new DomainNotFoundException("error.githubSync.source.notFound"));
        List<GithubSyncSkillPathResponse> items = jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (path.skill_path)
                           path.skill_path, path.commit_sha, path.outcome, path.reason,
                           path.retry_at, path.updated_at, path.ingestion_id
                    FROM github_sync_task task
                    JOIN github_sync_path_result path ON path.task_id = task.id
                    WHERE task.namespace_id = ? AND task.source_id = ?
                    ORDER BY path.skill_path, task.created_at DESC,
                             path.updated_at DESC, path.id DESC
                ), selected AS (
                    SELECT * FROM latest ORDER BY skill_path LIMIT ? OFFSET ?
                )
                SELECT path.skill_path, path.commit_sha, path.outcome, path.reason,
                       path.retry_at, path.updated_at, path.ingestion_id,
                       ingestion.status AS ingestion_status,
                       ingestion.last_error_code AS ingestion_error_code,
                       ingestion.target_skill_slug,
                       binding.skill_id, binding.upstream_presence,
                       binding.presence_commit_sha, binding.missing_since,
                       latest_ingestion.status AS latest_binding_ingestion_status,
                       binding.retry_requested_at IS NOT NULL AS retry_requested,
                       (binding.retry_requested_at IS NOT NULL AND
                        (binding.retry_scheduled_at IS NULL OR EXISTS (
                            SELECT 1 FROM github_sync_task retry_task
                            WHERE retry_task.source_id = binding.source_id
                              AND retry_task.mode = 'FULL'
                              AND retry_task.status IN ('QUEUED', 'LEASED', 'RETRY_WAIT')
                              AND retry_task.updated_at >= binding.retry_scheduled_at
                        ) OR EXISTS (
                            SELECT 1 FROM github_sync_package_ingestion pending
                            WHERE pending.namespace_id = binding.namespace_id
                              AND pending.repository_id = binding.repository_id
                              AND pending.skill_path = binding.skill_path
                              AND pending.created_at >= binding.retry_requested_at
                              AND pending.status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT',
                                                     'VALIDATED', 'READY_FOR_REVIEW')
                        ))) AS retry_in_progress,
                       review.id AS review_id, review.status AS review_status
                FROM selected path
                LEFT JOIN github_sync_package_ingestion ingestion ON ingestion.id = path.ingestion_id
                LEFT JOIN github_source_skill_binding binding
                       ON binding.source_id = ? AND binding.skill_path = path.skill_path
                LEFT JOIN github_sync_package_ingestion latest_ingestion
                       ON latest_ingestion.id = binding.latest_ingestion_id
                LEFT JOIN review_task review
                       ON review.github_sync_ingestion_id = ingestion.id
                ORDER BY path.skill_path
                """, (result, row) -> new GithubSyncSkillPathResponse(
                result.getString("skill_path"), result.getString("commit_sha"),
                result.getString("outcome"), result.getString("reason"),
                instant(result, "retry_at"), instant(result, "updated_at"),
                result.getObject("ingestion_id", UUID.class), result.getString("ingestion_status"),
                result.getString("ingestion_error_code"), result.getString("target_skill_slug"),
                result.getObject("skill_id", Long.class),
                result.getString("upstream_presence"), result.getString("presence_commit_sha"),
                instant(result, "missing_since"),
                result.getObject("review_id", Long.class), result.getString("review_status"),
                result.getString("latest_binding_ingestion_status"),
                result.getBoolean("retry_requested"),
                result.getBoolean("retry_in_progress"),
                GithubSkillPathPolicy.ignored(result.getString("skill_path"), ignorePatterns)),
                namespaceId, sourceId, size, (long) page * size, sourceId);
        Long total = jdbc.queryForObject("""
                SELECT count(DISTINCT path.skill_path)
                FROM github_sync_task task
                JOIN github_sync_path_result path ON path.task_id = task.id
                WHERE task.namespace_id = ? AND task.source_id = ?
                """, Long.class, namespaceId, sourceId);
        return new PageResponse<>(items, total == null ? 0 : total, page, size);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainBadRequestException("error.badRequest");
        }
    }

    private GithubSyncSourceSummaryResponse mapSource(ResultSet result) throws SQLException {
        return new GithubSyncSourceSummaryResponse(
                result.getLong("id"), result.getLong("repository_id"),
                result.getString("owner_login"), result.getString("repository_name"),
                result.getString("default_branch"), result.getString("visibility"),
                result.getString("status"), result.getString("last_observed_commit"),
                result.getString("last_fully_processed_commit"), instant(result, "next_check_at"),
                result.getString("last_error_code"), result.getObject("task_id", UUID.class),
                result.getString("task_status"), result.getString("task_mode"),
                result.getString("task_error_code"), result.getString("task_error_detail"),
                instant(result, "task_retry_at"),
                result.getLong("received_packages"), result.getLong("processing_packages"),
                result.getLong("pending_review_packages"), result.getLong("blocked_packages"),
                result.getLong("failed_packages"),
                result.getLong("tracked_skills"));
    }

    private GithubSyncRunSummaryResponse mapRun(ResultSet result) throws SQLException {
        return new GithubSyncRunSummaryResponse(result.getObject("id", UUID.class),
                result.getString("kind"), result.getString("status"),
                instant(result, "created_at"), instant(result, "finished_at"),
                instant(result, "next_retry_at"),
                result.getLong("task_count"), result.getLong("received_packages"),
                result.getLong("processing_packages"), result.getLong("pending_review_packages"),
                result.getLong("blocked_packages"), result.getLong("failed_packages"),
                result.getString("last_error_code"),
                result.getString("target_type") == null ? null : new Target(result.getString("target_type"), result.getString("target_value")),
                result.getLong("target_count"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
