package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GithubDiscoveryEntryResponse(
        UUID id,
        String entryType,
        String title,
        String ownerLogin,
        String repositoryName,
        Long repositoryId,
        String sourceVisibility,
        List<GithubSearchSelectorRequest> selectors,
        long ruleVersion,
        boolean enabled,
        int targetHours,
        Instant nextDueAt,
        Instant updatedAt,
        Execution latestExecution) {
    public record Execution(UUID taskId, UUID runId, String status, String errorCode, Instant retryAt) {}
    public GithubDiscoveryEntryResponse(UUID id, String entryType, String title, String ownerLogin,
            String repositoryName, Long repositoryId, String sourceVisibility, List<GithubSearchSelectorRequest> selectors,
            long ruleVersion, boolean enabled, int targetHours, Instant nextDueAt, Instant updatedAt) {
        this(id, entryType, title, ownerLogin, repositoryName, repositoryId, sourceVisibility, selectors,
                ruleVersion, enabled, targetHours, nextDueAt, updatedAt, null);
    }
}
