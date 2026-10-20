package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record GithubDeepDiscoveryConfigurationResponse(
        long version,
        boolean enabled,
        GithubDeepDiscoveryConfiguration.BudgetProfile budgetProfile,
        List<GithubDeepDiscoveryConfiguration.Exclusion> exclusions,
        @Schema(nullable = true, description = "尚未装载官方策略时为空；配置启用不表示正在执行") String strategyVersion,
        Instant updatedAt,
        @Schema(nullable = true) String updatedBy) {}
