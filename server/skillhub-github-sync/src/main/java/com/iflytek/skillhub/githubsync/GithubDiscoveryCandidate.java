package com.iflytek.skillhub.githubsync;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;
import java.util.List;

/** A public repository lead; it is not yet a verified source binding. */
public record GithubDiscoveryCandidate(long repositoryId, String owner, String name, String defaultBranch,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<GithubOfficialDiscoveryBatch.Provenance> officialProvenance) {
    public GithubDiscoveryCandidate(long repositoryId, String owner, String name, String defaultBranch) {
        this(repositoryId, owner, name, defaultBranch, null);
    }
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    public GithubDiscoveryCandidate {
        if (officialProvenance != null) {
            if (officialProvenance.isEmpty() || officialProvenance.size() > 16) {
                throw new IllegalArgumentException("Invalid official provenance count");
            }
            officialProvenance = List.copyOf(officialProvenance);
        }
        if (repositoryId <= 0 || owner == null || !OWNER.matcher(owner).matches()
                || name == null || !NAME.matcher(name).matches() || name.equals(".") || name.equals("..")
                || (defaultBranch != null && (defaultBranch.isBlank() || defaultBranch.length() > 256
                || defaultBranch.chars().anyMatch(Character::isISOControl)))) {
            throw new IllegalArgumentException("invalid public GitHub repository candidate");
        }
    }
}
