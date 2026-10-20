package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.UUID;

public record GithubSystemCredential(UUID id, String label, String fingerprint,
        String keyVersion, byte[] nonce, byte[] ciphertext, String status,
        Instant rateLimitedUntil, Instant lastUsedAt, Instant createdAt, Instant updatedAt) {
}
