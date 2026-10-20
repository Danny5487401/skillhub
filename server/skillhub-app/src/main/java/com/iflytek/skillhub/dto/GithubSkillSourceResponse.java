package com.iflytek.skillhub.dto;

import java.util.List;

/** Public provenance for the currently published GitHub-synced version. */
public record GithubSkillSourceResponse(
        String ownerLogin,
        String repositoryName,
        String skillPath,
        String commitSha,
        int syncRevision,
        String licenseStatus,
        List<LicenseDocument> licenseDocuments
) {
    public record LicenseDocument(String scope, String path, boolean bundled) {}
}
