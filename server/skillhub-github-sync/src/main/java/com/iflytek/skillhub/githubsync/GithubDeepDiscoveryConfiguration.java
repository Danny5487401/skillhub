package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Administrator intent for official discovery, independent of worker availability. */
public record GithubDeepDiscoveryConfiguration(
        long namespaceId,
        long version,
        boolean enabled,
        BudgetProfile budgetProfile,
        List<Exclusion> exclusions,
        String strategyVersion,
        Instant updatedAt,
        String updatedBy) {

    public GithubDeepDiscoveryConfiguration {
        exclusions = List.copyOf(exclusions);
    }

    public enum BudgetProfile {
        ECONOMY, BALANCED, ACCELERATED
    }

    public enum ExclusionKind {
        OWNER, REPOSITORY
    }

    public record Exclusion(ExclusionKind kind, String value) {
        private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");
        private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

        public Exclusion {
            if (kind == null || value == null || value.length() > 140) {
                throw new IllegalArgumentException("Invalid GitHub discovery exclusion");
            }
            value = value.trim().toLowerCase(Locale.ROOT);
            String[] parts = value.split("/", -1);
            if (!OWNER.matcher(parts[0]).matches()
                    || kind == ExclusionKind.OWNER && parts.length != 1
                    || kind == ExclusionKind.REPOSITORY && (parts.length != 2
                    || !REPOSITORY.matcher(parts[1]).matches()
                    || parts[1].equals(".") || parts[1].equals(".."))) {
                throw new IllegalArgumentException("Invalid GitHub discovery exclusion");
            }
        }
    }
}
