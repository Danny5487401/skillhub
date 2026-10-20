package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GithubSyncAdmissionPolicyUpdateRequest(
        @NotNull @Min(1) Integer globalHigh,
        @NotNull @Min(0) Integer globalLow,
        @NotNull @Min(1) Integer namespaceHigh,
        @NotNull @Min(0) Integer namespaceLow,
        @NotNull @Min(1) Integer candidatePendingLimit,
        @NotNull @Min(1) Integer candidatePerLeaseLimit) {
}
