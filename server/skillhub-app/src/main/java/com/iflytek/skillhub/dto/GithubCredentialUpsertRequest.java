package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubCredentialUpsertRequest(@NotBlank String label, @NotBlank String token) {
}
