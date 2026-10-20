package com.iflytek.skillhub.githubsync;

import java.util.UUID;

public record GithubPackageReportResult(Status status, UUID ingestionId, String objectKey) {
    public enum Status {
        READY_TO_CHECK_OBJECT, RECEIVED, LEASE_STALE, RESULT_CONFLICT,
        UPLOAD_NOT_READY, OBJECT_MISSING, OBJECT_MISMATCH, TOO_LARGE
    }

    public static GithubPackageReportResult rejected(Status status) {
        return new GithubPackageReportResult(status, null, null);
    }
}
