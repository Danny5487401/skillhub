package com.iflytek.skillhub.githubsync;

public enum GithubRepositoryCompletionResult {
    ACQUISITION_COMPLETE,
    PARTIAL,
    RETRY_WAIT,
    LEASE_STALE,
    COMMIT_MISMATCH,
    CHECKPOINT_CONFLICT,
    RESULT_CONFLICT,
    COVERAGE_CONFLICT
}
