package com.iflytek.skillhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Platform repository projections; registration is not a quality or publication verdict. */
public final class GithubSyncRepositoryResponse {
    private GithubSyncRepositoryResponse() {}
    @Schema(name = "GithubSyncRepositoryState")
    public enum State { REGISTERED, PENDING, VALIDATING, NO_VALID_SKILL, VALIDATION_FAILED, UNREACHABLE, BOUND_WITHOUT_SOURCE }
    @Schema(name = "GithubSyncRepositoryOriginType")
    public enum OriginType { CURATED_REPOSITORY, CURATED_OWNER, SEARCH_RULE, OFFICIAL_DISCOVERY, TEAM_CONFIG }
    @Schema(name = "GithubSyncRepositorySourceStatus")
    public enum SourceStatus { ACTIVE, PAUSED, UNREACHABLE }
    public record Page<T>(List<T> items, long total, int page, int size, Instant asOf) {}
    @Schema(name = "GithubSyncRepositorySummary")
    public record Summary(long repositoryId, Long sourceId, Long candidateId, String owner, String name,
                          Instant firstSeenAt, State state, SourceStatus sourceStatus, String candidateStatus,
                          List<OriginType> originTypes) {}
    @Schema(name = "GithubSyncRepositoryDetail")
    public record Detail(Summary repository, UUID latestRunId, Instant lastCheckedAt,
                         Instant nextCheckAt, String lastErrorCode) {}
    @Schema(name = "GithubSyncRepositoryOrigin")
    public record Origin(OriginType originType, String originKey, Instant firstSeenAt, Instant lastSeenAt,
                         UUID entryId, String channel, UUID queryId, UUID runId, Long seedRepositoryId) {}
    @Schema(name = "GithubSyncRunKind")
    public enum RunKind { DISCOVERY, REPOSITORY_SYNC }
    @Schema(name = "GithubSyncRunStatus")
    public enum RunStatus { QUEUED, LEASED, RETRY_WAIT, PARTIAL, ACQUISITION_COMPLETE, FAILED }
    @Schema(name = "GithubSyncRunTarget")
    public record Target(String type, String value) {}
}
