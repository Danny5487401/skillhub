package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record GithubSyncSchedulePolicyRequest(
        @Min(1) @Max(720) int repositoryHours,
        @Min(1) @Max(720) int curatedOwnerHours,
        @Min(1) @Max(720) int searchRuleHours) {}
