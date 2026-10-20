package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubTeamRepositoryRequest(@NotBlank String ownerLogin,
                                          @NotBlank String repositoryName,
                                          String visibility) {
}
