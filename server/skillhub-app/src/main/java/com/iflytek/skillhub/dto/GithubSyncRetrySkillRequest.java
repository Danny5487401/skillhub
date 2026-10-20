package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GithubSyncRetrySkillRequest(
        @NotBlank @Size(max = 1024) String skillPath) {
}
