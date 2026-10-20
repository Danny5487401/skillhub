package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GithubSearchPreviewRepository {
    UUID queue(String criteriaJson, String actorId, Instant now);

    Optional<Preview> find(UUID id);

    record Preview(UUID id, String status, String errorCode, Instant retryAt,
                   String snapshotJson, Instant createdAt, Instant updatedAt) {}
}
