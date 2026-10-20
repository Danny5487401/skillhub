package com.iflytek.skillhub.githubsync;

import java.util.UUID;

/** Finalize only a lease that can be delivered, inside the original claim transaction. */
public interface GithubOfficialDiscoveryBudgetRepository {
    void reserveSuccessfulClaim(UUID taskId, long generation);
}
