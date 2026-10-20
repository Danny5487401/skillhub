package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncSourceSummaryResponse(
        long id,
        long repositoryId,
        String ownerLogin,
        String repositoryName,
        String defaultBranch,
        String visibility,
        String status,
        String lastObservedCommit,
        String lastFullyProcessedCommit,
        Instant nextCheckAt,
        String lastErrorCode,
        UUID latestTaskId,
        String latestTaskStatus,
        String latestTaskMode,
        String latestTaskErrorCode,
        String latestTaskErrorDetail,
        Instant latestTaskRetryAt,
        long receivedPackages,
        long processingPackages,
        long pendingReviewPackages,
        long blockedPackages,
        long failedPackages,
        long trackedSkills) {
}
