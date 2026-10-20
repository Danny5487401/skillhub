package com.iflytek.skillhub.dto;

import java.util.UUID;

public record GithubSyncReviewQueueItemResponse(
        ReviewTaskResponse review,
        UUID ingestionId,
        UUID runId,
        long repositoryId,
        String ownerLogin,
        String repositoryName,
        String skillPath,
        String commitSha
) {}
