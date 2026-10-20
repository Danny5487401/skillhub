package com.iflytek.skillhub.githubsync;

import java.time.Instant;

public interface GithubSyncHistoryRepository {
    CleanupResult deleteExpiredDiscoveryHistory(Instant cutoff, int batchSize);

    CleanupResult purgeExpiredRepositoryDetails(Instant cutoff, Instant now, int batchSize);

    record CleanupResult(int tasks, int runs) {}
}
