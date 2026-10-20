package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncRunPathResponse(
        UUID taskId,
        Long sourceId,
        String repository,
        String skillPath,
        String commitSha,
        String outcome,
        String reason,
        String ingestionStatus,
        String ingestionErrorCode,
        String reviewStatus,
        Instant retryAt,
        Instant updatedAt) {
}
