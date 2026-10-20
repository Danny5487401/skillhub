package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicy;

import java.time.Instant;

public record GithubSyncAdmissionPolicyResponse(
        Integer globalHigh,
        Integer globalLow,
        Integer namespaceHigh,
        Integer namespaceLow,
        int candidatePendingLimit,
        int candidatePerLeaseLimit,
        boolean globalPaused,
        int globalUnresolvedCount,
        int platformUnresolvedCount,
        int platformPendingCandidateCount,
        boolean platformPaused,
        Instant updatedAt) {

    public static GithubSyncAdmissionPolicyResponse from(GithubSyncAdmissionPolicy policy,
                                                         int globalUnresolvedCount,
                                                         int platformUnresolvedCount,
                                                         int platformPendingCandidateCount,
                                                         boolean platformPaused) {
        return new GithubSyncAdmissionPolicyResponse(policy.globalHigh(), policy.globalLow(),
                policy.namespaceHigh(), policy.namespaceLow(), policy.candidatePendingLimit(),
                policy.candidatePerLeaseLimit(), policy.globalPaused(),
                globalUnresolvedCount, platformUnresolvedCount,
                platformPendingCandidateCount, platformPaused, policy.updatedAt());
    }
}
