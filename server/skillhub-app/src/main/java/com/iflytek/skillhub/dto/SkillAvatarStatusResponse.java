package com.iflytek.skillhub.dto;

public record SkillAvatarStatusResponse(String avatarUrl, Long candidateId, String candidateStatus,
                                        String machineStatus, String reviewStatus, String reviewReason,
                                        String candidateUrl, String namespace, String slug,
                                        java.time.Instant machineNextRetryAt, boolean automaticRetryExhausted) {}
