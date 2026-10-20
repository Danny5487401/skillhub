package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.githubsync.GithubSkillPathPolicy;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGithubSourceIgnoreRepository implements GithubSourceIgnoreRepository {
    private static final TypeReference<List<String>> PATTERNS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubSourceIgnoreRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<List<String>> find(long namespaceId, long sourceId) {
        return jdbc.query("SELECT ignore_patterns::text FROM github_source_binding "
                        + "WHERE namespace_id = ? AND id = ?",
                (result, row) -> parse(result.getString(1)), namespaceId, sourceId)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<List<String>> replace(long namespaceId, long sourceId, List<String> requested) {
        List<String> patterns = GithubSkillPathPolicy.normalizePatterns(requested);
        Optional<List<String>> current = jdbc.query("SELECT ignore_patterns::text FROM github_source_binding "
                        + "WHERE namespace_id = ? AND id = ? FOR UPDATE",
                (result, row) -> parse(result.getString(1)), namespaceId, sourceId)
                .stream().findFirst();
        if (current.isEmpty() || current.get().equals(patterns)) {
            return current;
        }
        try {
            jdbc.update("UPDATE github_source_binding SET ignore_patterns = ?::jsonb, "
                            + "last_fully_processed_commit = NULL, last_partial_commit = NULL, "
                            + "next_check_at = CURRENT_TIMESTAMP, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE namespace_id = ? AND id = ?",
                    mapper.writeValueAsString(patterns), namespaceId, sourceId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize GitHub ignore patterns", exception);
        }
        return Optional.of(patterns);
    }

    @Override
    @Transactional
    public boolean ignoredForSource(long sourceId, String skillPath) {
        List<String> patterns = jdbc.query("SELECT ignore_patterns::text FROM github_source_binding "
                        + "WHERE id = ? FOR SHARE",
                (result, row) -> parse(result.getString(1)), sourceId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("GitHub source is missing"));
        return GithubSkillPathPolicy.ignored(skillPath, patterns);
    }

    private List<String> parse(String json) {
        try {
            return mapper.readValue(json, PATTERNS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted GitHub ignore patterns", exception);
        }
    }
}
