package com.iflytek.skillhub.githubsync;

import java.time.Instant;

public record GithubSyncSchedulePolicy(Integer repositoryHours, int curatedOwnerHours,
                                       int searchRuleHours, Instant updatedAt) {}
