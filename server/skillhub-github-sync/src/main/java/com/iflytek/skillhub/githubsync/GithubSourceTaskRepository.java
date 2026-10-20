package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GithubSourceTaskRepository {
    int enqueueDue(Instant now, int maxSources);

    Optional<RescanRequest> requestRescan(long namespaceId, long sourceId, Instant now);

    record RescanRequest(String status, UUID taskId, String taskStatus, Instant nextCheckAt) {}
}
