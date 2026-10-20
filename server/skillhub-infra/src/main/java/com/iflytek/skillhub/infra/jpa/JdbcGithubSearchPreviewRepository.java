package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.githubsync.GithubSearchPreviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGithubSearchPreviewRepository implements GithubSearchPreviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubSearchPreviewRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public UUID queue(String criteriaJson, String actorId, Instant now) {
        Long namespaceId = jdbc.query("SELECT id FROM namespace WHERE slug = 'github' AND type = 'CATALOG'",
                result -> result.next() ? result.getLong(1) : null);
        if (namespaceId == null) {
            throw new IllegalStateException("GitHub catalog namespace is missing");
        }
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("entryType", "SEARCH_PREVIEW");
        snapshot.put("ruleVersion", 1);
        snapshot.put("candidateBudget", 1);
        snapshot.put("actorId", actorId);
        snapshot.set("criteria", readCriteria(criteriaJson));
        ObjectNode window = snapshot.putObject("searchWindow");
        window.put("mode", "FULL_RECHECK");
        window.put("until", now.toString());
        jdbc.update("INSERT INTO github_sync_run (id, namespace_id, kind, status, created_at) "
                + "VALUES (?, ?, 'DISCOVERY', 'QUEUED', ?)", runId, namespaceId, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO github_sync_task
                    (id, run_id, namespace_id, kind, status, dedupe_key, priority,
                     snapshot_json, due_at, created_at, updated_at)
                VALUES (?, ?, ?, 'DISCOVERY', 'QUEUED', ?, 10, ?::jsonb, ?, ?, ?)
                """, id, runId, namespaceId, "preview:" + id, snapshot.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    @Override
    public Optional<Preview> find(UUID id) {
        return jdbc.query("""
                SELECT id, status, last_error_code, retry_at, snapshot_json::text AS snapshot_json,
                       created_at, updated_at
                FROM github_sync_task
                WHERE id = ? AND kind = 'DISCOVERY' AND snapshot_json ->> 'entryType' = 'SEARCH_PREVIEW'
                """, result -> result.next() ? Optional.of(new Preview(
                result.getObject("id", UUID.class), result.getString("status"),
                result.getString("last_error_code"),
                result.getTimestamp("retry_at") == null ? null : result.getTimestamp("retry_at").toInstant(),
                result.getString("snapshot_json"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant())) : Optional.empty(), id);
    }

    private com.fasterxml.jackson.databind.JsonNode readCriteria(String value) {
        try {
            return mapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid validated search criteria", exception);
        }
    }
}
