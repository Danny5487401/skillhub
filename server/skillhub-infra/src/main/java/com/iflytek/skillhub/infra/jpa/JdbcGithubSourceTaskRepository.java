package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.iflytek.skillhub.githubsync.GithubSourceTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGithubSourceTaskRepository implements GithubSourceTaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcGithubSyncCapacityGate capacityGate;

    public JdbcGithubSourceTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                          JdbcGithubSyncCapacityGate capacityGate) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.capacityGate = capacityGate;
    }

    @Override
    @Transactional
    public int enqueueDue(Instant now, int maxSources) {
        if (maxSources < 1 || maxSources > 100) {
            throw new IllegalArgumentException("GitHub source scheduler batch must be 1 to 100");
        }
        List<Source> sources = jdbc.query("""
                WITH ranked AS (
                    SELECT source.id,
                           row_number() OVER (
                               PARTITION BY source.namespace_id
                               ORDER BY CASE WHEN COALESCE(source.next_check_at, source.created_at) <= ?
                                             THEN 0 ELSE 1 END,
                                        source.next_check_at NULLS FIRST, source.id) AS namespace_position
                    FROM github_source_binding source
                    WHERE source.status IN ('ACTIVE', 'UNREACHABLE')
                      AND (COALESCE(source.next_check_at, source.created_at) <= ?
                           OR source.manual_rescan_requested_at IS NOT NULL
                           OR (source.last_observed_commit IS DISTINCT FROM source.last_fully_processed_commit
                               AND source.last_observed_commit IS DISTINCT FROM source.last_partial_commit
                               AND (SELECT task.mode FROM github_sync_task task
                                    WHERE task.source_id = source.id
                                      AND task.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                                    ORDER BY task.completed_at DESC NULLS LAST, task.id DESC LIMIT 1)
                                   = 'HEAD_ONLY')
                           OR EXISTS (SELECT 1 FROM github_source_skill_binding binding
                                      WHERE binding.source_id = source.id
                                        AND binding.retry_requested_at IS NOT NULL
                                        AND binding.retry_scheduled_at IS NULL))
                      AND NOT EXISTS (
                          SELECT 1 FROM github_sync_task task
                          WHERE task.namespace_id = source.namespace_id
                            AND task.dedupe_key = 'repository:' || source.repository_id::text
                            AND task.status IN ('QUEUED', 'LEASED', 'RETRY_WAIT'))
                )
                SELECT source.id, source.namespace_id, source.repository_id,
                       source.owner_login, source.repository_name,
                       source.ignore_patterns::text AS ignore_patterns,
                       source.last_observed_commit, source.last_fully_processed_commit,
                       source.last_partial_commit,
                       source.manual_rescan_requested_at IS NOT NULL AS manual_rescan_requested,
                       EXISTS (SELECT 1 FROM github_source_skill_binding binding
                               WHERE binding.source_id = source.id
                                 AND binding.retry_requested_at IS NOT NULL
                                 AND binding.retry_scheduled_at IS NULL) AS retry_requested,
                       COALESCE(source.next_check_at, source.created_at) <= ? AS due
                FROM github_source_binding source
                JOIN ranked ON ranked.id = source.id
                ORDER BY ranked.namespace_position,
                         source.next_check_at NULLS FIRST, source.id
                LIMIT ? FOR UPDATE OF source SKIP LOCKED
                """, (result, row) -> new Source(
                result.getLong("id"), result.getLong("namespace_id"),
                result.getLong("repository_id"), result.getString("owner_login"),
                result.getString("repository_name"), result.getString("ignore_patterns"),
                result.getBoolean("due"),
                result.getString("last_observed_commit"),
                result.getString("last_fully_processed_commit"),
                result.getString("last_partial_commit"),
                result.getBoolean("manual_rescan_requested"),
                result.getBoolean("retry_requested")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), maxSources);
        int queued = 0;
        for (Source source : sources) {
            boolean full = capacityGate.allowsFullAcquisition(source.namespaceId(), now)
                    && !capacityGate.hasUnfinishedIngestion(source.namespaceId(), source.repositoryId())
                    && (source.manualRescanRequested() || source.retryRequested()
                        || (!Objects.equals(source.observedCommit(), source.processedCommit())
                            && !Objects.equals(source.observedCommit(), source.partialCommit())));
            if (!source.due() && !full) {
                continue;
            }
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            String dedupeKey = "repository:" + source.repositoryId();
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("repositoryId", source.repositoryId());
            snapshot.put("owner", source.owner());
            snapshot.put("name", source.name());
            try {
                snapshot.set("ignorePatterns", objectMapper.readTree(source.ignorePatterns()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid persisted GitHub ignore patterns", exception);
            }
            jdbc.update("""
                    INSERT INTO github_sync_run (id, namespace_id, kind, status, created_at)
                    VALUES (?, ?, 'REPOSITORY_SYNC', 'QUEUED', ?)
                    """, runId, source.namespaceId(), Timestamp.from(now));
            int inserted = jdbc.update("""
                    INSERT INTO github_sync_task
                        (id, run_id, namespace_id, source_id, kind, mode, status,
                         dedupe_key, priority, snapshot_json, due_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'REPOSITORY_SYNC', ?, 'QUEUED', ?, 100,
                            ?::jsonb, ?, ?, ?)
                    ON CONFLICT (namespace_id, dedupe_key)
                        WHERE status IN ('QUEUED', 'LEASED', 'RETRY_WAIT') DO NOTHING
                    """, taskId, runId, source.namespaceId(), source.id(),
                    full ? "FULL" : "HEAD_ONLY", dedupeKey, snapshot.toString(),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            if (inserted == 0) {
                jdbc.update("DELETE FROM github_sync_run WHERE id = ?", runId);
                continue;
            }
            if (full) {
                jdbc.update("""
                        UPDATE github_source_skill_binding
                        SET retry_scheduled_at = ?, updated_at = ?
                        WHERE source_id = ? AND retry_requested_at IS NOT NULL
                          AND retry_scheduled_at IS NULL
                        """, Timestamp.from(now), Timestamp.from(now), source.id());
            }
            queued++;
        }
        return queued;
    }

    @Override
    @Transactional
    public Optional<RescanRequest> requestRescan(long namespaceId, long sourceId, Instant now) {
        List<RescanSource> sources = jdbc.query("""
                SELECT status, next_check_at FROM github_source_binding
                WHERE namespace_id = ? AND id = ? FOR UPDATE
                """, (result, row) -> new RescanSource(result.getString("status"),
                result.getTimestamp("next_check_at") == null ? null
                        : result.getTimestamp("next_check_at").toInstant()), namespaceId, sourceId);
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        RescanSource source = sources.getFirst();
        if ("PAUSED".equals(source.status())) {
            return Optional.of(new RescanRequest("PAUSED", null, null, source.nextCheckAt()));
        }
        List<OpenTask> openTasks = jdbc.query("""
                SELECT id, status, mode FROM github_sync_task
                WHERE namespace_id = ? AND source_id = ?
                  AND status IN ('QUEUED', 'LEASED', 'RETRY_WAIT')
                ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE
                """, (result, row) -> new OpenTask(result.getObject("id", UUID.class),
                result.getString("status"), result.getString("mode")), namespaceId, sourceId);
        if (!openTasks.isEmpty()) {
            OpenTask task = openTasks.getFirst();
            if ("QUEUED".equals(task.status())) {
                jdbc.update("""
                        UPDATE github_sync_task
                        SET priority = GREATEST(priority, 200), due_at = LEAST(due_at, ?),
                            updated_at = ?
                        WHERE id = ? AND status = 'QUEUED'
                        """, Timestamp.from(now), Timestamp.from(now), task.id());
            }
            if ("HEAD_ONLY".equals(task.mode()) || "QUEUED".equals(task.status())) {
                jdbc.update("""
                        UPDATE github_source_binding
                        SET manual_rescan_requested_at = COALESCE(manual_rescan_requested_at, ?),
                            updated_at = ?
                        WHERE namespace_id = ? AND id = ?
                        """, Timestamp.from(now), Timestamp.from(now), namespaceId, sourceId);
            }
            return Optional.of(new RescanRequest("MERGED", task.id(), task.status(), source.nextCheckAt()));
        }
        jdbc.update("""
                UPDATE github_source_binding
                SET manual_rescan_requested_at = COALESCE(manual_rescan_requested_at, ?),
                    next_check_at = LEAST(COALESCE(next_check_at, ?), ?), updated_at = ?
                WHERE namespace_id = ? AND id = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), namespaceId, sourceId);
        return Optional.of(new RescanRequest("SCHEDULED", null, null,
                source.nextCheckAt() == null || source.nextCheckAt().isAfter(now)
                        ? now : source.nextCheckAt()));
    }

    private record Source(long id, long namespaceId, long repositoryId,
                          String owner, String name, String ignorePatterns, boolean due,
                          String observedCommit, String processedCommit, String partialCommit,
                          boolean manualRescanRequested,
                          boolean retryRequested) {}

    private record RescanSource(String status, Instant nextCheckAt) {}

    private record OpenTask(UUID id, String status, String mode) {}
}
