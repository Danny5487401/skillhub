package com.iflytek.skillhub.dto;

import java.time.Instant;

/** Contact and protocol compatibility, not proof of successful task execution. */
public record GithubOfficialWorkerAvailabilityResponse(
        String state, Instant lastSeenAt, long compatibleInstanceCount, Instant observedAt) {}
