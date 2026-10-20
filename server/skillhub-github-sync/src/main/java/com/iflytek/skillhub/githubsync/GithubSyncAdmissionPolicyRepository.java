package com.iflytek.skillhub.githubsync;

public interface GithubSyncAdmissionPolicyRepository {
    GithubSyncAdmissionPolicy get();

    GithubSyncAdmissionPolicy getForUpdate();

    GithubSyncAdmissionPolicy update(int globalHigh, int globalLow, int namespaceHigh, int namespaceLow,
                                     int candidatePendingLimit, int candidatePerLeaseLimit);
}
