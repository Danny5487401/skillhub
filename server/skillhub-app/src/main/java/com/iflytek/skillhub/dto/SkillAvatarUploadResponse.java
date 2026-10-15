package com.iflytek.skillhub.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

public record SkillAvatarUploadResponse(@NotNull String uploadId, @NotNull String previewUrl, @NotNull Instant expiresAt) {}
