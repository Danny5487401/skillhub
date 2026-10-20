package com.iflytek.skillhub.githubsync;

import java.util.UUID;

public record GithubRepositoryPathResult(String skillPath, Outcome outcome, UUID ingestionId, String reason) {
    public enum Outcome {
        ACCEPTED,
        IGNORED,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }
}
