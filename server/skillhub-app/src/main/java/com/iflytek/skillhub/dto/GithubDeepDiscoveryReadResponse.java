package com.iflytek.skillhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded read projections; no source contents, credentials, or raw error messages. */
public final class GithubDeepDiscoveryReadResponse {
    private GithubDeepDiscoveryReadResponse() {}
    @Schema(name = "GithubDeepDiscoveryChannel")
    public enum Channel { REPOSITORY_METADATA, README, OWNER_EXPANSION }
    @Schema(name = "GithubDeepDiscoveryRepositoryStatus")
    public enum RepositoryStatus { PENDING, VALIDATING, VALID, NO_VALID_SKILL, VALIDATION_FAILED, UNREACHABLE }
    public record DiscoveryPage<T>(List<T> items, long total, int page, int size, Instant asOf) {}
    @Schema(name = "GithubDeepDiscoveryWait")
    public record Wait(String reason, List<String> affectedWork, List<String> continuingWork,
                       Instant retryAt, String recoveryCondition, String action) {}
    @Schema(name = "GithubDeepDiscoveryRecent")
    public record Recent(Instant from, Instant until, Long newCandidateRepositories, Long firstValidRepositories,
                         Long newSkills, Long updatedVersions, boolean complete, Instant availableSince) {}
    @Schema(name = "GithubDeepDiscoveryCurrent")
    public record Current(long pendingRepositoryChecks, long pendingReviewVersions, Instant observedAt) {}
    @Schema(name = "GithubDeepDiscoverySummary")
    public record Summary(long configurationVersion, String strategyVersion, String state, Instant observedAt,
                          GithubOfficialWorkerAvailabilityResponse worker, Recent recent, Current current,
                          List<Wait> waits, List<UUID> recentRunIds) {}
    @Schema(name = "GithubDeepDiscoveryQuota")
    public record Quota(String resource, String subjectKey, String state, Long remaining, Instant observedAt,
                        Instant validUntil, Instant retryAt) {}
    @Schema(name = "GithubDeepDiscoveryChannelProgress")
    public record ChannelProgress(Channel channel, String state, long completedShards, long pendingShards,
                                  long gapShards, Instant lastProgressAt, List<Wait> waits) {}
    @Schema(name = "GithubDeepDiscoveryProgress")
    public record Progress(String strategyVersion, Instant observedAt, List<ChannelProgress> channels,
                           List<Quota> quotas, boolean quotaObservationsTruncated) {}
    @Schema(name = "GithubDeepDiscoveryRepositorySummary")
    public record RepositorySummary(long repositoryId, String owner, String name, Instant firstDiscoveredAt,
                                    Instant lastDiscoveredAt, RepositoryStatus status, List<Channel> channels,
                                    Long sourceId, boolean paused, String waitReason, long candidateId) {}
    @Schema(name = "GithubDeepDiscoveryRepositoryDetail")
    public record RepositoryDetail(RepositorySummary repository, Instant lastCheckedAt, Instant nextEligibleAt,
                                   String notImportedReason, List<Wait> waits, UUID latestRunId,
                                   Instant historyAvailableSince) {}
    @Schema(name = "GithubDeepDiscoveryProvenance")
    public record Provenance(UUID id, Channel channel, String strategyVersion, UUID queryId, long queryVersion,
                             UUID shardId, UUID runId, Instant firstSeenAt, Instant lastSeenAt, Long seedRepositoryId) {}
    @Schema(name = "GithubDeepDiscoveryPathResult")
    public record PathResult(String skillPath, String outcome, String reasonCode, Long skillId, Long versionId,
                             Long reviewId, UUID runId, Instant observedAt) {}
}
