package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record GithubSourceIgnoreRequest(@NotNull List<String> ignorePatterns) {}
