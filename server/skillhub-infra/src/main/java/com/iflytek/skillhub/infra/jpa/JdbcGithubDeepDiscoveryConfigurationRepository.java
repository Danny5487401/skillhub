package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfigurationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** JDBC keeps the version comparison and whole-configuration replacement in one SQL statement. */
@Repository
public class JdbcGithubDeepDiscoveryConfigurationRepository implements GithubDeepDiscoveryConfigurationRepository {
    private static final TypeReference<List<GithubDeepDiscoveryConfiguration.Exclusion>> EXCLUSIONS =
            new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubDeepDiscoveryConfigurationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public GithubDeepDiscoveryConfiguration get() {
        return jdbc.query("SELECT * FROM github_deep_discovery_config WHERE id = TRUE", result -> {
            if (!result.next()) {
                throw new IllegalStateException("GitHub deep discovery configuration is missing");
            }
            return map(result);
        });
    }

    @Override
    public Optional<GithubDeepDiscoveryConfiguration> update(long expectedVersion, boolean enabled,
            GithubDeepDiscoveryConfiguration.BudgetProfile budgetProfile,
            List<GithubDeepDiscoveryConfiguration.Exclusion> exclusions, String actorId) {
        String exclusionsJson;
        try {
            exclusionsJson = mapper.writeValueAsString(exclusions);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize GitHub discovery exclusions", exception);
        }
        return jdbc.query("""
                UPDATE github_deep_discovery_config
                SET enabled = ?, budget_profile = ?, exclusions_json = ?::jsonb,
                    config_version = config_version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                WHERE id = TRUE AND config_version = ?
                RETURNING *
                """, statement -> {
            statement.setBoolean(1, enabled);
            statement.setString(2, budgetProfile.name());
            statement.setString(3, exclusionsJson);
            statement.setString(4, actorId);
            statement.setLong(5, expectedVersion);
        }, result -> result.next() ? Optional.of(map(result)) : Optional.empty());
    }

    private GithubDeepDiscoveryConfiguration map(ResultSet result) throws SQLException {
        try {
            return new GithubDeepDiscoveryConfiguration(result.getLong("namespace_id"),
                    result.getLong("config_version"), result.getBoolean("enabled"),
                    GithubDeepDiscoveryConfiguration.BudgetProfile.valueOf(result.getString("budget_profile")),
                    mapper.readValue(result.getString("exclusions_json"), EXCLUSIONS),
                    result.getString("strategy_version"), result.getTimestamp("updated_at").toInstant(),
                    result.getString("updated_by"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted GitHub discovery exclusions", exception);
        }
    }
}
