package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted task facts returned by an atomic claim; the raw lease token is never stored here. */
public record GithubSyncTaskLease(
        UUID taskId,
        UUID runId,
        GithubSyncTaskKind kind,
        GithubSyncTaskMode mode,
        long namespaceId,
        Long sourceId,
        String snapshotJson,
        String discoveryCheckpoint,
        String fixedCommitSha,
        String pathCheckpoint,
        String incompleteSubtreesJson,
        List<String> retryPaths,
        List<String> acceptedPaths,
        long leaseGeneration,
        Instant leaseExpiresAt,
        List<String> requiredCapabilities, Long platformControlVersion) {

    public GithubSyncTaskLease {
        requiredCapabilities = List.copyOf(requiredCapabilities);
    }

    public GithubSyncTaskLease(UUID taskId, UUID runId, GithubSyncTaskKind kind,
            GithubSyncTaskMode mode, long namespaceId, Long sourceId, String snapshotJson,
            String discoveryCheckpoint, String fixedCommitSha, String pathCheckpoint,
            String incompleteSubtreesJson, List<String> retryPaths, List<String> acceptedPaths,
            long leaseGeneration, Instant leaseExpiresAt, List<String> requiredCapabilities) {
        this(taskId, runId, kind, mode, namespaceId, sourceId, snapshotJson, discoveryCheckpoint,
                fixedCommitSha, pathCheckpoint, incompleteSubtreesJson, retryPaths, acceptedPaths,
                leaseGeneration, leaseExpiresAt, requiredCapabilities, null);
    }

    public GithubSyncTaskLease(UUID taskId, UUID runId, GithubSyncTaskKind kind,
            GithubSyncTaskMode mode, long namespaceId, Long sourceId, String snapshotJson,
            String discoveryCheckpoint, String fixedCommitSha, String pathCheckpoint,
            String incompleteSubtreesJson, List<String> retryPaths, List<String> acceptedPaths,
            long leaseGeneration, Instant leaseExpiresAt) {
        this(taskId, runId, kind, mode, namespaceId, sourceId, snapshotJson, discoveryCheckpoint,
                fixedCommitSha, pathCheckpoint, incompleteSubtreesJson, retryPaths, acceptedPaths,
                leaseGeneration, leaseExpiresAt, List.of());
    }
}
