package com.iflytek.skillhub.githubsync;

public enum GithubDiscoveryBatchResult {
    ACCEPTED,
    LEASE_STALE,
    CHECKPOINT_CONFLICT,
    BUDGET_EXCEEDED,
    RESULT_CONFLICT
}
