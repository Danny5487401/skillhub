package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubCuratedRepositoryRequest(@NotBlank String ownerLogin,
                                             @NotBlank String repositoryName) {
}
