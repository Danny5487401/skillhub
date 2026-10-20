package com.iflytek.skillhub.dto;

import java.time.Instant;

public record GithubSyncWorkerStatusResponse(Instant lastSeenAt, boolean online) {}
