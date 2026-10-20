package com.iflytek.skillhub.infra.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** One-hop owner queries derive from durable validation evidence and independent official discovery. */
@Repository
public class JdbcGithubOfficialOwnerRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGithubOfficialOwnerRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void reconcile(long namespaceId, Instant now) {
        var seeds = jdbc.query("""
                SELECT DISTINCT ON (seed.github_owner_id) seed.github_owner_id, seed.owner_login, seed.repository_id
                FROM github_official_validated_seed seed
                JOIN github_discovery_candidate candidate ON candidate.namespace_id = seed.namespace_id
                    AND candidate.repository_id = seed.repository_id
                WHERE seed.namespace_id = ? AND EXISTS (
                    SELECT 1 FROM github_discovery_candidate_origin origin
                    JOIN github_deep_discovery_query query ON query.id::text = origin.origin_key
                        AND query.namespace_id = seed.namespace_id AND query.query_source = 'CATALOG'
                    WHERE origin.candidate_id = candidate.id AND origin.origin_type = 'OFFICIAL_DISCOVERY')
                AND NOT EXISTS (SELECT 1 FROM github_deep_discovery_query existing
                    WHERE existing.namespace_id = seed.namespace_id AND existing.github_owner_id = seed.github_owner_id
                        AND existing.query_source = 'OWNER')
                ORDER BY seed.github_owner_id, seed.validated_at, seed.repository_id LIMIT 100
                """, (result, row) -> new Seed(result.getLong(1), result.getString(2), result.getLong(3)), namespaceId);
        for (Seed seed : seeds) {
            var definition = new GithubOfficialDiscoveryCatalog.Definition("owner-" + seed.ownerId(), 1,
                    "OWNER_EXPANSION", "ECOSYSTEM", "OWNER_RECHECK",
                    new GithubOfficialDiscoveryCatalog.Query("OWNER", seed.login(), "OWNER_REPOSITORIES"));
            jdbc.update("""
                    INSERT INTO github_deep_discovery_query
                        (id, namespace_id, query_key, query_version, strategy_version, definition_json,
                         next_due_at, query_source, github_owner_id, owner_login, seed_repository_id)
                    VALUES (?, ?, ?, 1, ?, ?::jsonb, ?, 'OWNER', ?, ?, ?)
                    ON CONFLICT (namespace_id, github_owner_id) WHERE query_source = 'OWNER' DO NOTHING
                    """, UUID.randomUUID(), namespaceId, definition.key(), GithubOfficialDiscoveryCatalog.VERSION,
                    mapper.valueToTree(definition).toString(), Timestamp.from(now), seed.ownerId(), seed.login(), seed.repositoryId());
        }
    }

    private record Seed(long ownerId, String login, long repositoryId) {}
}
