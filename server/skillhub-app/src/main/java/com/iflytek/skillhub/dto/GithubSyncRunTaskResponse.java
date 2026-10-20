package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncRunTaskResponse(
        UUID id,
        Long sourceId,
        String repository,
        String kind,
        String mode,
        String status,
        String fixedCommitSha,
        String lastErrorCode,
        Instant retryAt,
        Instant createdAt,
        Instant completedAt) {
}
