package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record SkillLifecycleVersionResponse(
        Long id,
        String version,
        String status,
        String displayName,
        String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer syncRevision
) {
    public SkillLifecycleVersionResponse(Long id, String version, String status) {
        this(id, version, status, null, null, null);
    }
}
