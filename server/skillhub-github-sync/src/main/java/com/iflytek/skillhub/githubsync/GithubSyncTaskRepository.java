package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubSyncTaskRepository {
    Optional<GithubSyncTaskLease> claim(GithubSyncTaskKind kind, String workerId,
                                        String leaseTokenHash, Instant now, Instant expiresAt);

    default Optional<GithubSyncTaskLease> claim(GithubSyncTaskKind kind, String workerId,
            String leaseTokenHash, Instant now, Instant expiresAt, List<String> capabilities) {
        return claim(kind, workerId, leaseTokenHash, now, expiresAt);
    }

    Optional<Instant> renew(UUID taskId, long leaseGeneration, String leaseTokenHash,
                            Instant now, Instant expiresAt);

    GithubRepositoryMetadataResult updateRepositoryMetadata(UUID taskId, long leaseGeneration,
            String leaseTokenHash, long repositoryId, String owner, String name,
            String defaultBranch, Instant now);

    default GithubRepositoryMetadataResult updateRepositoryMetadata(UUID taskId, long leaseGeneration,
            String leaseTokenHash, long repositoryId, String owner, String name,
            String defaultBranch, Long ownerId, Instant now) {
        return updateRepositoryMetadata(taskId, leaseGeneration, leaseTokenHash, repositoryId, owner, name, defaultBranch, now);
    }

    GithubPackageAdmissionResult admitPackage(UUID taskId, long leaseGeneration,
            String leaseTokenHash, String commitSha, String skillPath, Instant now);

    boolean completeHeadOnly(UUID taskId, long leaseGeneration, String leaseTokenHash,
                             String observedCommitSha, Instant now, Instant nextCheckAt);

    Optional<Instant> fail(UUID taskId, long leaseGeneration, String leaseTokenHash,
                           GithubSyncFailureReason reason, String detail,
                           Instant requestedRetryAt, Instant now);

    GithubDiscoveryBatchResult recordDiscoveryBatch(UUID taskId, long leaseGeneration,
            String leaseTokenHash, GithubDiscoveryBatch batch, String requestSha256, Instant now);

    GithubDiscoveryCompletionResult completeDiscovery(UUID taskId, long leaseGeneration,
            String leaseTokenHash, String checkpoint, boolean complete,
            Instant retryAt, GithubSyncDeferralReason deferralReason, Instant now);

    boolean completePreview(UUID taskId, long leaseGeneration, String leaseTokenHash,
                            String resultJson, Instant now);

    GithubRepositoryCompletionResult completeRepository(UUID taskId, long leaseGeneration,
            String leaseTokenHash, String commitSha, boolean complete, String pathCheckpoint,
            List<String> incompleteSubtrees, List<GithubRepositoryPathResult> pathResults,
            Instant retryAt, GithubSyncDeferralReason deferralReason, Instant now, Instant nextCheckAt);
}
