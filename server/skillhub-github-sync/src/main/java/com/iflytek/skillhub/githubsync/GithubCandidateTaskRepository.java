package com.iflytek.skillhub.githubsync;

import java.time.Instant;

public interface GithubCandidateTaskRepository {
    int enqueueDue(Instant now, int maxCandidates);

    int reconcileCompleted(Instant now, int maxCandidates);
}
