package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.githubsync.GithubCredentialCipher;
import com.iflytek.skillhub.githubsync.GithubSystemCredential;
import com.iflytek.skillhub.githubsync.GithubCredentialQuota;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GithubCredentialResponse(UUID id, String label, String fingerprint,
        String status, String keyVersion, boolean keyAvailable, Instant rateLimitedUntil,
        Instant lastUsedAt, Instant createdAt, Instant updatedAt,
        List<GithubCredentialQuota> quotas) {
    public static GithubCredentialResponse from(GithubSystemCredential credential,
                                                GithubCredentialCipher cipher,
                                                List<GithubCredentialQuota> quotas) {
        return new GithubCredentialResponse(credential.id(), credential.label(),
                credential.fingerprint(), credential.status(), credential.keyVersion(),
                cipher.supports(credential.keyVersion()), credential.rateLimitedUntil(),
                credential.lastUsedAt(), credential.createdAt(), credential.updatedAt(), quotas);
    }
}
