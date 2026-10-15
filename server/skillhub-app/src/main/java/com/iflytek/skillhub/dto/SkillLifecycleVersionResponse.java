package com.iflytek.skillhub.dto;

public record SkillLifecycleVersionResponse(
        Long id,
        String version,
        String status,
        String displayName,
        String summary
) {
    public SkillLifecycleVersionResponse(Long id, String version, String status) {
        this(id, version, status, null, null);
    }
}
