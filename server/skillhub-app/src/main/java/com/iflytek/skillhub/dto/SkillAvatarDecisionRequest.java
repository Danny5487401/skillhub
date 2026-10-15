package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record SkillAvatarDecisionRequest(boolean approved, @NotBlank String reason) {}
