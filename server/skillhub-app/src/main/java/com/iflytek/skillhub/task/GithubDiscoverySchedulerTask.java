package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubDiscoveryEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class GithubDiscoverySchedulerTask {
    private static final Logger log = LoggerFactory.getLogger(GithubDiscoverySchedulerTask.class);
    private final GithubDiscoveryEntryRepository entries;

    public GithubDiscoverySchedulerTask(GithubDiscoveryEntryRepository entries) {
        this.entries = entries;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.discovery-scheduler-interval-ms:60000}")
    public void enqueueDue() {
        int queued = entries.enqueueDue(Instant.now(), 10);
        if (queued > 0) {
            log.info("Queued {} GitHub discovery tasks", queued);
        }
    }
}
