package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record GithubSearchRuleRequest(
        @NotBlank String title,
        @NotEmpty List<GithubSearchSelectorRequest> selectors,
        Integer targetHours) {
}
