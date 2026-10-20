package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubCuratedOwnerRequest(@NotBlank String ownerLogin, Integer targetHours) {
}
