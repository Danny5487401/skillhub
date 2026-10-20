package com.iflytek.skillhub.githubsync;

import java.util.HashSet;
import java.util.List;

public record GithubDiscoveryBatch(String previousCheckpoint, String checkpoint,
                                   List<GithubDiscoveryCandidate> repositories, int deferredCount,
                                   GithubSyncDeferralReason deferralReason) {
    public GithubDiscoveryBatch {
        if (checkpoint == null || checkpoint.isBlank() || checkpoint.length() > 4096
                || checkpoint.chars().anyMatch(Character::isISOControl)
                || (previousCheckpoint != null && (previousCheckpoint.length() > 4096
                || previousCheckpoint.chars().anyMatch(Character::isISOControl)))
                || repositories == null || repositories.size() > 100 || deferredCount < 0
                || (deferredCount > 0 && deferralReason == null)) {
            throw new IllegalArgumentException("invalid GitHub discovery batch");
        }
        var seen = new HashSet<Long>();
        for (GithubDiscoveryCandidate repository : repositories) {
            if (repository == null || !seen.add(repository.repositoryId())) {
                throw new IllegalArgumentException("invalid or duplicate repository in discovery batch");
            }
        }
        repositories = List.copyOf(repositories);
    }
}
