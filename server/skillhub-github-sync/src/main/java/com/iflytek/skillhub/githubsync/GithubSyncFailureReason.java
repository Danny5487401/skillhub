package com.iflytek.skillhub.githubsync;

public enum GithubSyncFailureReason {
    RETRYABLE_ACQUISITION,
    GITHUB_RATE_LIMITED,
    SOURCE_UNREADABLE,
    INCOMPLETE_TREE,
    INTERNAL_FAILURE
}
