package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.UUID;

/** Shared conservative gates, without adding balances across unknown GitHub identities. */
public interface GithubOfficialQuotaRepository {
    record Observation(String resource, Instant observedAt, String state, Integer remaining,
                       Instant resetAt, Integer retryAfterSeconds) {}
    boolean record(UUID taskId, long generation, String leaseTokenHash, UUID expectedCredentialId,
                   Observation observation, Instant now);
    Instant cooldown(String resource, Instant now);
    Instant cooldownForLease(UUID taskId, Instant now);
    default void deferClaim(UUID taskId, long generation, Instant due, Instant now) {
        deferClaim(taskId, generation, due, now, "QUOTA_COOLDOWN");
    }
    void deferClaim(UUID taskId, long generation, Instant due, Instant now, String reason);
    void clearClaimWait(UUID taskId, long generation);
}
