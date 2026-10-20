package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record GithubDeepDiscoveryConfigurationRequest(
        @NotNull @Min(1) Long expectedVersion,
        @NotNull Boolean enabled,
        @NotNull GithubDeepDiscoveryConfiguration.BudgetProfile budgetProfile,
        @NotNull @Size(max = 1000) List<@NotNull @Valid ExclusionRequest> exclusions) {

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown GitHub discovery configuration field");
    }

    public record ExclusionRequest(
            @NotNull GithubDeepDiscoveryConfiguration.ExclusionKind kind,
            @NotBlank @Size(max = 140) String value) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown GitHub discovery exclusion field");
        }
    }
}
