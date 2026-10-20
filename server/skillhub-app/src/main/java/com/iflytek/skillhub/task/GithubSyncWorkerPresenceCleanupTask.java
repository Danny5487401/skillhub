package com.iflytek.skillhub.task;

import com.iflytek.skillhub.repository.GithubSyncWorkerPresenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Discard expired process diagnostics without touching durable task progress or legacy contact. */
@Component
public class GithubSyncWorkerPresenceCleanupTask {
    private static final int BATCH_SIZE = 500;
    private final GithubSyncWorkerPresenceRepository presence;
    private final Duration retention;

    public GithubSyncWorkerPresenceCleanupTask(GithubSyncWorkerPresenceRepository presence,
            @Value("${skillhub.github-sync.completed-detail-retention:P90D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("GitHub sync presence retention must be at least one day");
        }
        this.presence = presence;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.history-cleanup-interval-ms:3600000}")
    public void cleanup() {
        Instant before = Instant.now().minus(retention);
        for (int batch = 0; batch < 10; batch++) {
            if (presence.deleteExpiredInstances(before, BATCH_SIZE) < BATCH_SIZE) {
                break;
            }
        }
    }
}
