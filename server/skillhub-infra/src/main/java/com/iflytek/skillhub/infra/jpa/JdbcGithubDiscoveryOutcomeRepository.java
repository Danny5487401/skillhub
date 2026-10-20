package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubDiscoveryOutcomeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGithubDiscoveryOutcomeRepository implements GithubDiscoveryOutcomeRepository {
    private final JdbcTemplate jdbc;
    public JdbcGithubDiscoveryOutcomeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void candidateCreated(long namespaceId, long repositoryId, Instant now) {
        record(namespaceId, repositoryId, "CANDIDATE_CREATED", repositoryId, now);
    }
    @Override public void firstValid(long namespaceId, long repositoryId, Instant now) {
        record(namespaceId, repositoryId, "FIRST_VALID", repositoryId, now);
    }
    @Override public void publication(long namespaceId, long repositoryId, long skillId,
                                     long versionId, boolean newSkill, Instant now) {
        record(namespaceId, repositoryId, newSkill ? "NEW_SKILL" : "UPDATED_VERSION",
                newSkill ? skillId : versionId, now);
    }
    private void record(long namespaceId, long repositoryId, String type, long subject, Instant now) {
        jdbc.update("""
                INSERT INTO github_discovery_outcome_event
                  (namespace_id,repository_id,event_type,subject_key,occurred_at,official_at_event,query_ids,first_occurrence_known)
                SELECT ?,?,?,?,?, cardinality(origins.ids)>0, origins.ids,
                    NOT (?='FIRST_VALID' AND EXISTS(SELECT 1 FROM github_discovery_legacy_unknown_validation
                        WHERE namespace_id=? AND repository_id=?))
                FROM (SELECT COALESCE(array_agg(DISTINCT q.id), ARRAY[]::uuid[]) ids
                    FROM github_discovery_candidate c
                    JOIN github_discovery_candidate_origin o ON o.candidate_id=c.id
                    JOIN github_deep_discovery_query q ON q.id::text=o.origin_key
                    WHERE c.namespace_id=? AND c.repository_id=? AND o.origin_type='OFFICIAL_DISCOVERY') origins
                ON CONFLICT (namespace_id,event_type,subject_key) DO NOTHING
                """, namespaceId, repositoryId, type, subject, Timestamp.from(now), type, namespaceId, repositoryId, namespaceId, repositoryId);
    }
    @Override public void provenance(long candidateId, UUID queryId, String strategyVersion,
                                     UUID shardId, UUID runId, Long seedRepositoryId) {
        jdbc.update("""
                UPDATE github_discovery_candidate_origin SET recent_shard_id=?,recent_run_id=?,
                    recent_seed_repository_id=?,latest_strategy_version=?
                WHERE candidate_id=? AND origin_type='OFFICIAL_DISCOVERY' AND origin_key=?
                """, shardId, runId, seedRepositoryId, strategyVersion, candidateId, queryId.toString());
    }
}
