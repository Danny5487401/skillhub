package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GithubSyncManualBindingRequest(
        @NotNull @Positive Long skillId,
        @NotBlank @Size(min = 10, max = 1000) String reason
) {}
