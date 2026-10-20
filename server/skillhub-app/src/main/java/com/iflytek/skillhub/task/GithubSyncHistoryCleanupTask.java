package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubSyncHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class GithubSyncHistoryCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(GithubSyncHistoryCleanupTask.class);
    private static final int DISCOVERY_BATCH_SIZE = 500;
    private static final int REPOSITORY_BATCH_SIZE = 25;
    private final GithubSyncHistoryRepository history;
    private final Duration retention;

    public GithubSyncHistoryCleanupTask(GithubSyncHistoryRepository history,
            @Value("${skillhub.github-sync.completed-detail-retention:P90D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("GitHub sync history retention must be at least one day");
        }
        this.history = history;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.history-cleanup-interval-ms:3600000}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        int tasks = 0;
        int runs = 0;
        for (int batch = 0; batch < 10; batch++) {
            var discovery = history.deleteExpiredDiscoveryHistory(cutoff, DISCOVERY_BATCH_SIZE);
            var repository = history.purgeExpiredRepositoryDetails(cutoff, Instant.now(),
                    REPOSITORY_BATCH_SIZE);
            tasks += discovery.tasks() + repository.tasks();
            runs += discovery.runs() + repository.runs();
            if (discovery.tasks() < DISCOVERY_BATCH_SIZE && discovery.runs() < DISCOVERY_BATCH_SIZE
                    && repository.tasks() < REPOSITORY_BATCH_SIZE
                    && repository.runs() < REPOSITORY_BATCH_SIZE) {
                break;
            }
        }
        if (tasks > 0 || runs > 0) {
            log.info("Cleaned up expired GitHub sync history [tasks={}, runs={}]", tasks, runs);
        }
    }
}
