package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubSourceTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class GithubSourceSchedulerTask {
    private static final Logger log = LoggerFactory.getLogger(GithubSourceSchedulerTask.class);
    private final GithubSourceTaskRepository sources;

    public GithubSourceSchedulerTask(GithubSourceTaskRepository sources) {
        this.sources = sources;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.source-scheduler-interval-ms:60000}")
    public void enqueueDue() {
        int queued = sources.enqueueDue(Instant.now(), 10);
        if (queued > 0) {
            log.info("Queued {} GitHub source sync tasks", queued);
        }
    }
}
