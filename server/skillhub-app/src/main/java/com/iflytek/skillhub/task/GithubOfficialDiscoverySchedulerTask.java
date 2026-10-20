package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class GithubOfficialDiscoverySchedulerTask {
    private static final Logger log = LoggerFactory.getLogger(GithubOfficialDiscoverySchedulerTask.class);
    private final GithubOfficialDiscoveryRepository queries;

    public GithubOfficialDiscoverySchedulerTask(GithubOfficialDiscoveryRepository queries) {
        this.queries = queries;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.discovery-scheduler-interval-ms:60000}")
    public void enqueueDue() {
        int queued = queries.enqueueDue(Instant.now(), 10);
        if (queued > 0) {
            log.info("Queued {} official GitHub discovery tasks", queued);
        }
    }
}
