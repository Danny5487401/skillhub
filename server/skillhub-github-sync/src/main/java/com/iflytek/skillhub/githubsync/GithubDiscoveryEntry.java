package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.UUID;

public record GithubDiscoveryEntry(
        UUID id,
        long namespaceId,
        String entryType,
        String title,
        String ownerLogin,
        String repositoryName,
        Long repositoryId,
        String sourceVisibility,
        String criteriaJson,
        long ruleVersion,
        boolean enabled,
        int targetIntervalSeconds,
        Instant nextDueAt,
        Instant createdAt,
        Instant updatedAt) {
}
