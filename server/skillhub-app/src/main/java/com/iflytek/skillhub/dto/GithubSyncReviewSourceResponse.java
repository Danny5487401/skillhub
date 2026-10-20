package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GithubSyncReviewSourceResponse(
        UUID ingestionId,
        UUID runId,
        Long sourceId,
        long repositoryId,
        String ownerLogin,
        String repositoryName,
        String commitSha,
        String skillPath,
        String contentSha256,
        int syncRevision,
        String ingestionStatus,
        Instant receivedAt,
        LicenseEvidence evidence,
        Long baseVersionId,
        List<FileChange> fileChanges
) {
    public GithubSyncReviewSourceResponse withFileChanges(List<FileChange> changes) {
        return new GithubSyncReviewSourceResponse(ingestionId, runId, sourceId, repositoryId,
                ownerLogin, repositoryName, commitSha, skillPath, contentSha256,
                syncRevision, ingestionStatus, receivedAt, evidence, baseVersionId, changes);
    }

    public record FileChange(String path, String changeType, Long oldSize, Long newSize,
                             String oldSha256, String newSha256) {}

    public record LicenseEvidence(String status, List<EvidenceDocument> documents) {}

    public record EvidenceDocument(String scope, String path, String gitSha,
                                   String sha256, long size, boolean bundled,
                                   String text) {}
}
