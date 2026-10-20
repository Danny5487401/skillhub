package com.iflytek.skillhub.githubsync;

import java.util.List;
import java.util.Optional;

public interface GithubDeepDiscoveryConfigurationRepository {
    GithubDeepDiscoveryConfiguration get();

    Optional<GithubDeepDiscoveryConfiguration> update(long expectedVersion, boolean enabled,
            GithubDeepDiscoveryConfiguration.BudgetProfile budgetProfile,
            List<GithubDeepDiscoveryConfiguration.Exclusion> exclusions, String actorId);
}
