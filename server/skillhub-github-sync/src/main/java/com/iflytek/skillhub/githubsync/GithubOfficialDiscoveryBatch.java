package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GithubOfficialDiscoveryBatch(UUID shardId, String coverage, Integer requestsUsed,
                                           List<Range> childRanges, String gapReason, String ownerLogin) {
    public GithubOfficialDiscoveryBatch(UUID shardId, String coverage, Integer requestsUsed,
                                        List<Range> childRanges, String gapReason) {
        this(shardId, coverage, requestsUsed, childRanges, gapReason, null);
    }
    public record Range(Instant since, Instant until, String partition) {}
    public record Provenance(String channel, UUID queryId, long queryVersion, String strategyVersion,
                             UUID shardId, Long seedRepositoryId) {}
}
