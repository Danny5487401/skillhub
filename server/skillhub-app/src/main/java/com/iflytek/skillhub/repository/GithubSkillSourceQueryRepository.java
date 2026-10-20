package com.iflytek.skillhub.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.dto.GithubSkillSourceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class GithubSkillSourceQueryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GithubSkillSourceQueryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Call only after SkillQueryService has authorized the viewer and selected the published version. */
    public Optional<GithubSkillSourceResponse> findPublishedVersion(long versionId) {
        return jdbc.query("""
                SELECT COALESCE(source.owner_login, task.snapshot_json ->> 'owner') AS owner_login,
                       COALESCE(source.repository_name, task.snapshot_json ->> 'name') AS repository_name,
                       ingestion.skill_path, ingestion.commit_sha, version.github_sync_revision,
                       ingestion.license_evidence_json::text AS evidence_json
                FROM skill_version version
                JOIN github_sync_package_ingestion ingestion
                  ON ingestion.id = version.github_sync_ingestion_id
                 AND ingestion.published_version_id = version.id
                JOIN github_sync_task task ON task.id = ingestion.task_id
                LEFT JOIN github_source_binding source
                  ON source.namespace_id = ingestion.namespace_id
                 AND source.repository_id = ingestion.repository_id
                WHERE version.id = ? AND version.status = 'PUBLISHED'
                """, result -> {
            if (!result.next()) {
                return Optional.empty();
            }
            JsonNode evidence = evidence(result.getString("evidence_json"));
            return Optional.of(new GithubSkillSourceResponse(
                    result.getString("owner_login"), result.getString("repository_name"),
                    result.getString("skill_path"), result.getString("commit_sha"),
                    result.getInt("github_sync_revision"),
                    evidence.path("status").asText("UNKNOWN"), licenseDocuments(evidence)));
        }, versionId);
    }

    private JsonNode evidence(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid stored GitHub license evidence", exception);
        }
    }

    private List<GithubSkillSourceResponse.LicenseDocument> licenseDocuments(JsonNode evidence) {
        List<GithubSkillSourceResponse.LicenseDocument> documents = new ArrayList<>();
        for (JsonNode document : evidence.path("documents")) {
            documents.add(new GithubSkillSourceResponse.LicenseDocument(
                    document.path("scope").asText(), document.path("path").asText(),
                    document.path("bundled").asBoolean()));
        }
        return List.copyOf(documents);
    }
}
