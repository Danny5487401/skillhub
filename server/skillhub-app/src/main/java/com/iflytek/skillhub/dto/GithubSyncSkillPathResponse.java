package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSyncSkillPathResponse(
        String skillPath,
        String commitSha,
        String acquisitionOutcome,
        String acquisitionReason,
        Instant acquisitionRetryAt,
        Instant acquisitionUpdatedAt,
        UUID ingestionId,
        String ingestionStatus,
        String ingestionErrorCode,
        String targetSkillSlug,
        Long skillId,
        String upstreamPresence,
        String presenceCommitSha,
        Instant missingSince,
        Long reviewId,
        String reviewStatus,
        String latestBindingIngestionStatus,
        boolean retryRequested,
        boolean retryInProgress,
        boolean ignoredByCurrentPolicy) {
}
