package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.List;

public record GithubSyncWorkerHeartbeatRequest(String workerId, Integer protocolMajor, List<String> capabilities) {
    public GithubSyncWorkerHeartbeatRequest {
        boolean legacy = workerId == null && protocolMajor == null && capabilities == null;
        if (!legacy && (invalid(workerId) || !Integer.valueOf(1).equals(protocolMajor)
                || capabilities == null || capabilities.size() > 32
                || capabilities.stream().anyMatch(GithubSyncWorkerHeartbeatRequest::invalid))) {
            throw new IllegalArgumentException("Invalid GitHub worker heartbeat");
        }
        capabilities = capabilities == null ? null : List.copyOf(capabilities);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported GitHub worker heartbeat field");
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl);
    }
}
