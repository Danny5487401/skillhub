package com.iflytek.skillhub.dto;

import java.time.Instant;

public record GithubSyncSchedulePolicyResponse(
        Integer repositoryHours,
        int curatedOwnerHours,
        int searchRuleHours,
        String effectiveRepositoryInterval,
        Instant updatedAt) {}
