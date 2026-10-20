package com.iflytek.skillhub.dto;

import java.util.UUID;

public record GithubSyncManualBindingResponse(
        UUID ingestionId,
        long sourceId,
        long skillId,
        String skillSlug,
        String skillPath,
        String status
) {}
