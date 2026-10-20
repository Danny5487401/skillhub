package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncRunDetailResponse(
        UUID id,
        String kind,
        String status,
        Instant createdAt,
        Instant finishedAt,
        long taskCount,
        long pathCount,
        Instant nextRetryAt,
        long receivedPackages,
        long processingPackages,
        long pendingReviewPackages,
        long blockedPackages,
        long failedPackages,
        String lastErrorCode) {
}
