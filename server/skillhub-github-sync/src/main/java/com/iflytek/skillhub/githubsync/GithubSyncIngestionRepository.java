package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubSyncIngestionRepository {
    Optional<ClaimedIngestion> claim(UUID validationToken, Instant now, Instant expiresAt);

    boolean validated(UUID ingestionId, UUID validationToken, String contentSha256,
                      int warningCount, String evidenceJson, Instant now);

    boolean failed(UUID ingestionId, UUID validationToken, String reason,
                   Instant retryAt, String pathOutcome, Instant now);

    List<CleanupIngestion> claimCleanup(Instant now, Duration signedUrlGrace, int limit);

    void markCleaned(UUID ingestionId, Instant now);

    record ClaimedIngestion(UUID id, UUID validationToken, String objectKey, long sizeBytes,
                            String sha256, String skillPath, Long sourceId, int attempt) {}
    record CleanupIngestion(UUID id, String objectKey) {}
}
