package com.iflytek.skillhub.dto;

import java.util.List;

public record GithubSourceIgnoreResponse(long sourceId, List<String> ignorePatterns) {}
