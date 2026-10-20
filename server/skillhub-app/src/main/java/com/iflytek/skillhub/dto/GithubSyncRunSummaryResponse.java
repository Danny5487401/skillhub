package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncRunSummaryResponse(
        UUID id,
        String kind,
        String status,
        Instant createdAt,
        Instant finishedAt,
        Instant nextRetryAt,
        long taskCount,
        long receivedPackages,
        long processingPackages,
        long pendingReviewPackages,
        long blockedPackages,
        long failedPackages,
        String lastErrorCode,
        GithubSyncRepositoryResponse.Target target,
        long targetCount) {
    public GithubSyncRunSummaryResponse(UUID id, String kind, String status, Instant createdAt, Instant finishedAt,
            Instant nextRetryAt, long taskCount, long receivedPackages, long processingPackages,
            long pendingReviewPackages, long blockedPackages, long failedPackages, String lastErrorCode) {
        this(id, kind, status, createdAt, finishedAt, nextRetryAt, taskCount, receivedPackages, processingPackages,
                pendingReviewPackages, blockedPackages, failedPackages, lastErrorCode, null, 0);
    }
}
