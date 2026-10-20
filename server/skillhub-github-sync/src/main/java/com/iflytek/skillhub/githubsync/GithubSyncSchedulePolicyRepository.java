package com.iflytek.skillhub.githubsync;

public interface GithubSyncSchedulePolicyRepository {
    GithubSyncSchedulePolicy get();

    GithubSyncSchedulePolicy getForUpdate();

    GithubSyncSchedulePolicy update(int repositoryHours, int curatedOwnerHours, int searchRuleHours);
}
