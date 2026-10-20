package com.iflytek.skillhub.githubsync;

import java.time.Instant;

/** Configured high/low watermarks for unresolved GitHub sync work. */
public record GithubSyncAdmissionPolicy(
        Integer globalHigh,
        Integer globalLow,
        Integer namespaceHigh,
        Integer namespaceLow,
        int candidatePendingLimit,
        int candidatePerLeaseLimit,
        boolean globalPaused,
        Instant updatedAt) {
}
