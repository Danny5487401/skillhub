package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Locks the blocked ingestion and target identity while reserving a namespace-local binding. */
@Repository
public class JdbcGithubSyncManualBindingRepository {
    private final JdbcTemplate jdbc;

    public JdbcGithubSyncManualBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Binding(UUID ingestionId, long sourceId, long namespaceId, long repositoryId,
                          String skillPath, String skillSlug, long skillId, String previousPath) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Binding bind(long namespaceId, UUID ingestionId, long targetSkillId) {
        Conflict conflict = jdbc.query("""
                SELECT ingestion.namespace_id, ingestion.repository_id, ingestion.task_id,
                       ingestion.commit_sha, ingestion.content_sha256, ingestion.skill_path,
                       ingestion.target_skill_slug, ingestion.status, ingestion.last_error_code,
                       source.id AS source_id
                FROM github_sync_package_ingestion ingestion
                JOIN github_source_binding source
                  ON source.namespace_id = ingestion.namespace_id
                 AND source.repository_id = ingestion.repository_id
                WHERE ingestion.id = ? AND ingestion.namespace_id = ?
                FOR UPDATE OF ingestion
                """, result -> result.next() ? new Conflict(
                result.getLong("namespace_id"), result.getLong("repository_id"),
                result.getObject("task_id", UUID.class), result.getString("commit_sha"),
                result.getString("content_sha256"), result.getString("skill_path"),
                result.getString("target_skill_slug"),
                result.getString("status"), result.getString("last_error_code"),
                result.getLong("source_id")) : null, ingestionId, namespaceId);
        if (conflict == null) {
            throw new DomainNotFoundException("error.githubSync.binding.conflictNotFound");
        }
        if ("BLOCKED".equals(conflict.status()) && "PATH_MOVE_AMBIGUOUS".equals(conflict.errorCode())) {
            return bindPathMove(conflict, ingestionId, targetSkillId);
        }
        if (!"BLOCKED".equals(conflict.status()) || !"SLUG_CONFLICT".equals(conflict.errorCode())
                || conflict.skillSlug() == null) {
            throw new DomainBadRequestException("error.githubSync.binding.notResolvable");
        }
        Target target = jdbc.query("""
                SELECT id, slug, status, github_sync_managed FROM skill
                WHERE id = ? AND namespace_id = ? FOR UPDATE
                """, result -> result.next() ? new Target(
                result.getLong("id"), result.getString("slug"),
                result.getString("status"), result.getBoolean("github_sync_managed"))
                : null, targetSkillId, namespaceId);
        if (target == null || !target.slug().equals(conflict.skillSlug())
                || !"ACTIVE".equals(target.status()) || target.syncManaged()) {
            throw new DomainBadRequestException("error.githubSync.binding.invalidTarget");
        }
        Boolean pendingUserReview = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM review_task
                  WHERE skill_id = ? AND status = 'PENDING' AND origin_type = 'USER')
                """, Boolean.class, targetSkillId);
        if (Boolean.TRUE.equals(pendingUserReview)) {
            throw new DomainBadRequestException("error.githubSync.binding.pendingReview");
        }
        Long boundSource = jdbc.query("""
                SELECT source_id FROM github_source_skill_binding
                WHERE namespace_id = ? AND skill_id = ? FOR UPDATE
                """, result -> result.next() ? result.getLong("source_id") : null,
                namespaceId, targetSkillId);
        if (boundSource != null) {
            throw new DomainBadRequestException("error.githubSync.binding.alreadyBound");
        }
        Long boundPath = jdbc.query("""
                SELECT id FROM github_source_skill_binding
                WHERE namespace_id = ? AND repository_id = ? AND skill_path = ? FOR UPDATE
                """, result -> result.next() ? result.getLong("id") : null,
                namespaceId, conflict.repositoryId(), conflict.skillPath());
        if (boundPath != null) {
            throw new DomainBadRequestException("error.githubSync.binding.alreadyBound");
        }
        try {
            jdbc.update("""
                    INSERT INTO github_source_skill_binding
                        (source_id, namespace_id, repository_id, skill_path, skill_id,
                         latest_content_sha256, latest_ingestion_id)
                    VALUES (?, ?, ?, ?, ?, NULL, NULL)
                    """, conflict.sourceId(), namespaceId, conflict.repositoryId(),
                    conflict.skillPath(), targetSkillId);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainBadRequestException("error.githubSync.binding.alreadyBound");
        }
        int resumed = jdbc.update("""
                UPDATE github_sync_package_ingestion
                SET status = 'VALIDATED', last_error_code = NULL, target_skill_slug = NULL,
                    publication_retry_at = NULL, updated_at = ?
                WHERE id = ? AND status = 'BLOCKED' AND last_error_code = 'SLUG_CONFLICT'
                """, Timestamp.from(Instant.now()), ingestionId);
        if (resumed != 1) {
            throw new IllegalStateException("GitHub sync conflict changed while binding");
        }
        return new Binding(ingestionId, conflict.sourceId(), namespaceId,
                conflict.repositoryId(), conflict.skillPath(), conflict.skillSlug(), targetSkillId, null);
    }

    private Binding bindPathMove(Conflict conflict, UUID ingestionId, long targetSkillId) {
        Long lockedSource = jdbc.query("""
                SELECT id FROM github_source_binding WHERE id = ? AND namespace_id = ?
                  AND repository_id = ? FOR UPDATE
                """, result -> result.next() ? result.getLong("id") : null,
                conflict.sourceId(), conflict.namespaceId(), conflict.repositoryId());
        if (lockedSource == null) {
            throw new DomainBadRequestException("error.githubSync.binding.notResolvable");
        }
        Boolean complete = jdbc.query("""
                SELECT task.mode = 'FULL' AND task.status = 'ACQUISITION_COMPLETE'
                       AND task.fixed_commit_sha = ?
                       AND COALESCE(task.snapshot_json -> 'ignorePatterns', '[]'::jsonb)
                           = source.ignore_patterns
                       AND NOT EXISTS (SELECT 1 FROM github_sync_task newer
                           WHERE newer.source_id = task.source_id
                             AND newer.kind = 'REPOSITORY_SYNC' AND newer.mode = 'FULL'
                             AND (newer.created_at, newer.id) > (task.created_at, task.id))
                       AS complete
                FROM github_sync_task task
                JOIN github_source_binding source ON source.id = task.source_id
                WHERE task.id = ? AND task.source_id = ?
                  AND task.namespace_id = ?
                """, result -> result.next() && result.getBoolean("complete"),
                conflict.commitSha(), conflict.taskId(), conflict.sourceId(), conflict.namespaceId());
        Boolean currentAccepted = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM github_sync_path_result
                  WHERE task_id = ? AND commit_sha = ? AND skill_path = ?
                    AND outcome = 'ACCEPTED' AND ingestion_id = ?)
                """, Boolean.class, conflict.taskId(), conflict.commitSha(), conflict.skillPath(), ingestionId);
        if (!Boolean.TRUE.equals(complete) || !Boolean.TRUE.equals(currentAccepted)) {
            throw new DomainBadRequestException("error.githubSync.binding.notResolvable");
        }
        PathTarget target = jdbc.query("""
                SELECT binding.skill_path, skill.slug
                FROM github_source_skill_binding binding
                JOIN skill ON skill.id = binding.skill_id
                WHERE binding.source_id = ? AND binding.namespace_id = ?
                  AND binding.repository_id = ? AND binding.skill_id = ?
                  AND binding.skill_path <> ? AND binding.latest_content_sha256 IS NOT NULL
                  AND skill.namespace_id = ? AND skill.status = 'ACTIVE'
                  AND skill.github_sync_managed = TRUE
                FOR UPDATE OF binding, skill
                """, result -> result.next() ? new PathTarget(result.getString("skill_path"),
                result.getString("slug")) : null,
                conflict.sourceId(), conflict.namespaceId(), conflict.repositoryId(),
                targetSkillId, conflict.skillPath(), conflict.namespaceId());
        if (target == null) {
            throw new DomainBadRequestException("error.githubSync.binding.invalidTarget");
        }
        Boolean oldPathPresent = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM github_sync_path_result
                  WHERE task_id = ? AND commit_sha = ? AND skill_path = ?)
                """, Boolean.class, conflict.taskId(), conflict.commitSha(), target.previousPath());
        if (Boolean.TRUE.equals(oldPathPresent)) {
            throw new DomainBadRequestException("error.githubSync.binding.invalidTarget");
        }
        // Path observations do not replace the current content/review target. Keeping its
        // ingestion identity also preserves pending decisions and explicit rejected retries.
        int moved = jdbc.update("""
                UPDATE github_source_skill_binding
                SET skill_path = ?, upstream_presence = 'PRESENT',
                    presence_commit_sha = ?, missing_since = NULL,
                    updated_at = ?
                WHERE source_id = ? AND namespace_id = ? AND repository_id = ?
                  AND skill_path = ? AND skill_id = ?
                  AND NOT EXISTS (SELECT 1 FROM github_source_skill_binding occupied
                      WHERE occupied.namespace_id = ? AND occupied.repository_id = ?
                        AND occupied.skill_path = ?)
                """, conflict.skillPath(), conflict.commitSha(),
                Timestamp.from(Instant.now()), conflict.sourceId(), conflict.namespaceId(),
                conflict.repositoryId(), target.previousPath(), targetSkillId,
                conflict.namespaceId(), conflict.repositoryId(), conflict.skillPath());
        if (moved != 1) {
            throw new DomainBadRequestException("error.githubSync.binding.alreadyBound");
        }
        int resumed = jdbc.update("""
                UPDATE github_sync_package_ingestion
                SET status = 'VALIDATED', last_error_code = NULL,
                    publication_retry_at = NULL, updated_at = ?
                WHERE id = ? AND status = 'BLOCKED' AND last_error_code = 'PATH_MOVE_AMBIGUOUS'
                """, Timestamp.from(Instant.now()), ingestionId);
        if (resumed != 1) {
            throw new IllegalStateException("GitHub sync path conflict changed while binding");
        }
        return new Binding(ingestionId, conflict.sourceId(), conflict.namespaceId(),
                conflict.repositoryId(), conflict.skillPath(), target.skillSlug(),
                targetSkillId, target.previousPath());
    }

    private record Conflict(long namespaceId, long repositoryId, UUID taskId, String commitSha,
                            String contentSha256, String skillPath, String skillSlug,
                            String status, String errorCode, long sourceId) {}
    private record Target(long id, String slug, String status, boolean syncManaged) {}
    private record PathTarget(String previousPath, String skillSlug) {}
}
