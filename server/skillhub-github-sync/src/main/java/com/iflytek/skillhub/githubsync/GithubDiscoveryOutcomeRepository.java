package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.UUID;

/** Durable factual outcomes, written in the same transaction as their business transition. */
public interface GithubDiscoveryOutcomeRepository {
    void candidateCreated(long namespaceId, long repositoryId, Instant now);
    void firstValid(long namespaceId, long repositoryId, Instant now);
    void publication(long namespaceId, long repositoryId, long skillId, long versionId,
                     boolean newSkill, Instant now);
    void provenance(long candidateId, UUID queryId, String strategyVersion, UUID shardId,
                    UUID runId, Long seedRepositoryId);
}
