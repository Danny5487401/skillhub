package com.iflytek.skillhub.githubsync;

import java.time.Instant;

public record GithubCredentialQuota(String resource, int remaining,
                                    Instant resetAt, Instant observedAt) {}
