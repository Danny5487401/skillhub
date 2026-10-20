package com.iflytek.skillhub.githubsync;

public enum GithubSyncDeferralReason {
    BUDGET,
    RATE_LIMIT,
    BACKPRESSURE,
    CAPACITY,
    LOCAL_DISK_BUDGET
}
