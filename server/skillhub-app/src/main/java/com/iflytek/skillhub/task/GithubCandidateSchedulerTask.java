package com.iflytek.skillhub.task;

import com.iflytek.skillhub.githubsync.GithubCandidateTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class GithubCandidateSchedulerTask {
    private static final Logger log = LoggerFactory.getLogger(GithubCandidateSchedulerTask.class);
    private final GithubCandidateTaskRepository candidates;

    public GithubCandidateSchedulerTask(GithubCandidateTaskRepository candidates) {
        this.candidates = candidates;
    }

    @Scheduled(fixedDelayString = "${skillhub.github-sync.candidate-scheduler-interval-ms:60000}")
    public void enqueueDue() {
        Instant now = Instant.now();
        int reconciled = candidates.reconcileCompleted(now, 20);
        int queued = candidates.enqueueDue(now, 10);
        if (queued > 0 || reconciled > 0) {
            log.info("GitHub candidate scheduling: queued={}, reconciled={}", queued, reconciled);
        }
    }
}
