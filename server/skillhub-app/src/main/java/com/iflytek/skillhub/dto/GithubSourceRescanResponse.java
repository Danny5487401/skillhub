package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.UUID;

public record GithubSourceRescanResponse(long sourceId, String status, UUID taskId,
                                         String taskStatus, Instant nextCheckAt) {}
