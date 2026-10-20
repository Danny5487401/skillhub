package com.iflytek.skillhub.githubsync;

import java.util.UUID;

public record GithubPackageAdmissionResult(Status status, UUID admissionId) {
    public enum Status {
        ADMITTED, LEASE_STALE, COMMIT_MISMATCH, BACKPRESSURE_ACTIVE, PATH_IGNORED
    }

    public static GithubPackageAdmissionResult rejected(Status status) {
        return new GithubPackageAdmissionResult(status, null);
    }
}
