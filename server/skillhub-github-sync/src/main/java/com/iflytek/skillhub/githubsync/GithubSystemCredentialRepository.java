package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubSystemCredentialRepository {
    List<GithubSystemCredential> list();
    List<GithubCredentialQuota> quotas(UUID credentialId);
    Optional<GithubSystemCredential> find(UUID id);
    default boolean hasConfiguredCredentials() { return !list().isEmpty(); }
    Optional<GithubSystemCredential> availableForClaim(Instant now, String resource);
    void create(GithubSystemCredential credential);
    boolean replace(UUID id, String label, String fingerprint, String keyVersion, byte[] nonce, byte[] ciphertext);
    boolean setEnabled(UUID id, boolean enabled);
    boolean assignToLease(UUID taskId, long generation, UUID credentialId,
                          String fingerprint, Instant now);
    boolean markInvalid(UUID taskId, long generation, String leaseTokenHash, UUID credentialId, Instant now);
    boolean markRateLimited(UUID taskId, long generation, String leaseTokenHash,
                            UUID credentialId, Instant retryAt, Instant now);
    boolean recordQuotaForLease(UUID taskId, long generation, String leaseTokenHash,
                                UUID credentialId, String resource, int remaining,
                                Instant resetAt, Instant now);
    boolean recordQuotaForTaskLease(UUID taskId, long generation, String leaseTokenHash,
                                    String resource, int remaining, Instant resetAt, Instant now);
    void recordQuota(UUID credentialId, String resource, int remaining, Instant resetAt, Instant now);
}
