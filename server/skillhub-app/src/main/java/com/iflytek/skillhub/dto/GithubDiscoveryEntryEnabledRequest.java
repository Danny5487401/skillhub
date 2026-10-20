package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotNull;

public record GithubDiscoveryEntryEnabledRequest(@NotNull Boolean enabled) {
}
