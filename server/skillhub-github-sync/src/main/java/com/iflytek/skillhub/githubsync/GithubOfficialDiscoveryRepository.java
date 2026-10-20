package com.iflytek.skillhub.githubsync;

import java.time.Instant;

public interface GithubOfficialDiscoveryRepository {
    int enqueueDue(Instant now, int maxQueries);
}
