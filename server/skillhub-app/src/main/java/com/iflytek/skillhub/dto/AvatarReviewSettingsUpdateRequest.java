package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Min;

public record AvatarReviewSettingsUpdateRequest(boolean machineEnabled, boolean manualEnabled,
                                                @Min(0) long version) {}
