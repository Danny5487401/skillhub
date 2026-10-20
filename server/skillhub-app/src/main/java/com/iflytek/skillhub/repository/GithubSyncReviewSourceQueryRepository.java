package com.iflytek.skillhub.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.dto.GithubSyncReviewSourceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GithubSyncReviewSourceQueryRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public GithubSyncReviewSourceQueryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
    }

    public record QueueSource(UUID ingestionId, UUID runId, long repositoryId,
                              String ownerLogin, String repositoryName,
                              String skillPath, String commitSha) {}

    public Map<Long, QueueSource> findQueueSources(List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return namedJdbc.query("""
                SELECT review.id AS review_id, ingestion.id AS ingestion_id, task.run_id,
                       ingestion.repository_id,
                       COALESCE(task.snapshot_json ->> 'owner', source.owner_login) AS owner_login,
                       COALESCE(task.snapshot_json ->> 'name', source.repository_name) AS repository_name,
                       ingestion.skill_path, ingestion.commit_sha
                FROM review_task review
                JOIN github_sync_package_ingestion ingestion ON ingestion.id = review.github_sync_ingestion_id
                JOIN github_sync_task task ON task.id = ingestion.task_id
                LEFT JOIN github_source_binding source
                  ON source.namespace_id = ingestion.namespace_id
                 AND source.repository_id = ingestion.repository_id
                WHERE review.id IN (:reviewIds) AND review.origin_type = 'GITHUB_SYNC'
                """, Map.of("reviewIds", reviewIds), result -> {
            Map<Long, QueueSource> sources = new HashMap<>();
            while (result.next()) {
                sources.put(result.getLong("review_id"), new QueueSource(
                        result.getObject("ingestion_id", UUID.class),
                        result.getObject("run_id", UUID.class),
                        result.getLong("repository_id"),
                        result.getString("owner_login"), result.getString("repository_name"),
                        result.getString("skill_path"), result.getString("commit_sha")));
            }
            return sources;
        });
    }

    public Optional<GithubSyncReviewSourceResponse> findByReviewId(Long reviewId) {
        Optional<SourceRow> row = jdbc.query("""
                SELECT ingestion.id, task.run_id, source.id AS source_id, ingestion.repository_id,
                       COALESCE(task.snapshot_json ->> 'owner', source.owner_login) AS owner_login,
                       COALESCE(task.snapshot_json ->> 'name', source.repository_name) AS repository_name,
                       ingestion.commit_sha,
                       ingestion.skill_path, ingestion.content_sha256, ingestion.status,
                       ingestion.created_at, ingestion.license_evidence_json::text AS evidence_json,
                       review.skill_version_id, reviewed_version.github_sync_revision,
                       (SELECT previous.id FROM skill_version previous
                        WHERE previous.skill_id = reviewed_version.skill_id
                          AND previous.id <> review.skill_version_id
                          AND previous.published_at <= review.submitted_at
                        ORDER BY previous.published_at DESC, previous.id DESC
                        LIMIT 1) AS base_version_id
                FROM review_task review
                JOIN skill_version reviewed_version ON reviewed_version.id = review.skill_version_id
                JOIN github_sync_package_ingestion ingestion
                  ON ingestion.id = review.github_sync_ingestion_id
                JOIN github_sync_task task ON task.id = ingestion.task_id
                LEFT JOIN github_source_binding source
                  ON source.namespace_id = ingestion.namespace_id
                 AND source.repository_id = ingestion.repository_id
                WHERE review.id = ? AND review.origin_type = 'GITHUB_SYNC'
                """, result -> result.next() ? Optional.of(new SourceRow(new GithubSyncReviewSourceResponse(
                result.getObject("id", UUID.class), result.getObject("run_id", UUID.class),
                result.getObject("source_id", Long.class),
                result.getLong("repository_id"), result.getString("owner_login"),
                result.getString("repository_name"), result.getString("commit_sha"),
                result.getString("skill_path"), result.getString("content_sha256"),
                result.getInt("github_sync_revision"),
                result.getString("status"), result.getTimestamp("created_at").toInstant(),
                evidence(result.getString("evidence_json")),
                result.getObject("base_version_id", Long.class),
                List.of()), result.getLong("skill_version_id"))) : Optional.empty(), reviewId);
        return row.map(value -> value.response().withFileChanges(
                fileChanges(value.versionId(), value.response().baseVersionId())));
    }

    private record SourceRow(GithubSyncReviewSourceResponse response, long versionId) {}

    private List<GithubSyncReviewSourceResponse.FileChange> fileChanges(long versionId, Long baseVersionId) {
        return jdbc.query("""
                SELECT COALESCE(current_file.file_path, previous_file.file_path) AS path,
                       CASE WHEN previous_file.id IS NULL THEN 'ADDED'
                            WHEN current_file.id IS NULL THEN 'REMOVED'
                            ELSE 'MODIFIED' END AS change_type,
                       previous_file.file_size AS old_size, current_file.file_size AS new_size,
                       previous_file.sha256 AS old_sha256, current_file.sha256 AS new_sha256
                FROM (SELECT * FROM skill_file WHERE version_id = ?) current_file
                FULL OUTER JOIN (SELECT * FROM skill_file WHERE version_id = ?) previous_file
                  ON previous_file.file_path = current_file.file_path
                WHERE current_file.id IS NULL OR previous_file.id IS NULL
                   OR current_file.sha256 IS DISTINCT FROM previous_file.sha256
                ORDER BY path
                """, (result, rowNum) -> new GithubSyncReviewSourceResponse.FileChange(
                result.getString("path"), result.getString("change_type"),
                result.getObject("old_size", Long.class), result.getObject("new_size", Long.class),
                result.getString("old_sha256"), result.getString("new_sha256")),
                versionId, baseVersionId);
    }

    private GithubSyncReviewSourceResponse.LicenseEvidence evidence(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<GithubSyncReviewSourceResponse.EvidenceDocument> documents = new ArrayList<>();
            for (JsonNode document : root.path("documents")) {
                documents.add(new GithubSyncReviewSourceResponse.EvidenceDocument(
                        document.path("scope").asText(), document.path("path").asText(),
                        optional(document, "gitSha"), optional(document, "sha256"),
                        document.path("size").asLong(), document.path("bundled").asBoolean(),
                        optional(document, "text")));
            }
            return new GithubSyncReviewSourceResponse.LicenseEvidence(
                    root.path("status").asText("UNKNOWN"), List.copyOf(documents));
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid stored GitHub license evidence", exception);
        }
    }

    private String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
