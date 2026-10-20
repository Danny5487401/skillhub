package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubCandidateTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGithubCandidateTaskRepository implements GithubCandidateTaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcGithubSyncCapacityGate capacityGate;

    public JdbcGithubCandidateTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                             JdbcGithubSyncCapacityGate capacityGate) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.capacityGate = capacityGate;
    }

    @Override
    @Transactional
    public int enqueueDue(Instant now, int maxCandidates) {
        requireBatchSize(maxCandidates);
        jdbc.queryForObject("SELECT enabled FROM github_deep_discovery_config WHERE id = TRUE FOR SHARE", Boolean.class);
        List<Candidate> due = jdbc.query("""
                SELECT candidate.id, candidate.namespace_id, candidate.repository_id,
                       candidate.owner_login, candidate.repository_name, eligibility.official_only,
                       EXISTS (
                           SELECT 1 FROM github_discovery_candidate_origin origin
                           WHERE origin.candidate_id = candidate.id
                             AND origin.origin_type = 'CURATED_OWNER'
                       ) AS curated_owner
                FROM github_discovery_candidate candidate
                JOIN github_candidate_discovery_eligibility eligibility ON eligibility.candidate_id = candidate.id
                WHERE candidate.status IN ('PENDING', 'REJECTED')
                  AND candidate.next_validation_at <= ?
                  AND eligibility.acquisition_allowed
                ORDER BY candidate.next_validation_at, candidate.id
                LIMIT ? FOR UPDATE OF candidate SKIP LOCKED
                """, (result, row) -> new Candidate(
                result.getLong("id"), result.getLong("namespace_id"),
                result.getLong("repository_id"), result.getString("owner_login"),
                result.getString("repository_name"), result.getBoolean("curated_owner"), result.getBoolean("official_only")),
                Timestamp.from(now), maxCandidates);
        int queued = 0;
        for (Candidate candidate : due) {
            Integer source = jdbc.query("""
                    SELECT 1 FROM github_source_binding
                    WHERE namespace_id = ? AND repository_id = ?
                    """, result -> result.next() ? 1 : null,
                    candidate.namespaceId(), candidate.repositoryId());
            if (source != null) {
                jdbc.update("""
                        UPDATE github_discovery_candidate
                        SET status = 'BOUND', last_error_code = NULL WHERE id = ?
                        """, candidate.id());
                continue;
            }
            if (!capacityGate.allowsFullAcquisition(candidate.namespaceId(), now)) {
                continue;
            }
            UUID runId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            String dedupeKey = "repository:" + candidate.repositoryId();
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("candidateId", candidate.id());
            snapshot.put("repositoryId", candidate.repositoryId());
            snapshot.put("owner", candidate.owner());
            snapshot.put("name", candidate.name());
            jdbc.update("""
                    INSERT INTO github_sync_run (id, namespace_id, kind, status, created_at)
                    VALUES (?, ?, 'REPOSITORY_SYNC', 'QUEUED', ?)
                    """, runId, candidate.namespaceId(), Timestamp.from(now));
            int inserted = jdbc.update("""
                    INSERT INTO github_sync_task
                        (id, run_id, namespace_id, kind, mode, status, dedupe_key,
                         priority, snapshot_json, due_at, created_at, updated_at, required_capabilities)
                    VALUES (?, ?, ?, 'REPOSITORY_SYNC', 'FULL', 'QUEUED', ?, ?, ?::jsonb, ?, ?, ?, CASE WHEN ? THEN ARRAY['official-pause/v1'] ELSE ARRAY[]::text[] END)
                    ON CONFLICT (namespace_id, dedupe_key)
                        WHERE status IN ('QUEUED', 'LEASED', 'RETRY_WAIT') DO NOTHING
                    """, taskId, runId, candidate.namespaceId(), dedupeKey,
                    candidate.curatedOwner() ? 70 : 40, snapshot.toString(),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), candidate.officialOnly());
            if (inserted == 0) {
                jdbc.update("DELETE FROM github_sync_run WHERE id = ?", runId);
                continue;
            }
            jdbc.update("""
                    UPDATE github_discovery_candidate
                    SET status = 'VALIDATING', validation_task_id = ?, last_error_code = NULL
                    WHERE id = ?
                    """, taskId, candidate.id());
            queued++;
        }
        return queued;
    }

    @Override
    @Transactional
    public int reconcileCompleted(Instant now, int maxCandidates) {
        requireBatchSize(maxCandidates);
        List<Long> bound = jdbc.query("""
                SELECT candidate.id FROM github_discovery_candidate candidate
                WHERE candidate.status IN ('VALIDATING', 'UNREACHABLE')
                  AND EXISTS (SELECT 1 FROM github_source_binding source
                              WHERE source.namespace_id = candidate.namespace_id
                                AND source.repository_id = candidate.repository_id)
                ORDER BY candidate.id
                LIMIT ? FOR UPDATE OF candidate SKIP LOCKED
                """, (result, row) -> result.getLong("id"), maxCandidates);
        for (Long id : bound) {
            jdbc.update("""
                    UPDATE github_discovery_candidate
                    SET status = 'BOUND', last_error_code = NULL WHERE id = ?
                    """, id);
        }
        List<Completed> completed = jdbc.query("""
                SELECT candidate.id, task.status FROM github_discovery_candidate candidate
                JOIN github_sync_task task ON task.id = candidate.validation_task_id
                WHERE candidate.status IN ('VALIDATING', 'UNREACHABLE')
                  AND task.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                  AND NOT EXISTS (
                      SELECT 1 FROM github_sync_package_ingestion ingestion
                      WHERE ingestion.task_id = task.id
                        AND ingestion.status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT',
                                                 'VALIDATED', 'READY_FOR_REVIEW', 'BLOCKED'))
                  AND NOT EXISTS (
                      SELECT 1 FROM github_source_binding source
                      WHERE source.namespace_id = candidate.namespace_id
                        AND source.repository_id = candidate.repository_id)
                ORDER BY candidate.id
                LIMIT ? FOR UPDATE OF candidate SKIP LOCKED
                """, (result, row) -> new Completed(result.getLong("id"), result.getString("status")),
                maxCandidates - bound.size());
        for (Completed candidate : completed) {
            jdbc.update("""
                    UPDATE github_discovery_candidate
                    SET status = 'REJECTED', next_validation_at = ?,
                        last_error_code = ? WHERE id = ?
                    """, Timestamp.from(now.plus(Duration.ofDays(7))),
                    "FAILED".equals(candidate.taskStatus()) ? "REPOSITORY_FAILED" : "NO_VALID_SKILL",
                    candidate.id());
        }
        return bound.size() + completed.size();
    }

    private void requireBatchSize(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("GitHub candidate batch size must be 1 to 100");
        }
    }

    private record Candidate(long id, long namespaceId, long repositoryId,
                             String owner, String name, boolean curatedOwner, boolean officialOnly) {}

    private record Completed(long id, String taskStatus) {}
}
