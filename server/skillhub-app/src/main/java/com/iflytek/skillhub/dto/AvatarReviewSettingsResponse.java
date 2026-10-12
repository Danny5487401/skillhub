package com.iflytek.skillhub.dto;

import java.time.Instant;

public record AvatarReviewSettingsResponse(boolean machineEnabled, boolean manualEnabled,
                                           long version, Instant updatedAt) {}
