package com.iflytek.skillhub.dto;

import java.util.List;

public record ReviewSkillDetailResponse(
        SkillDetailResponse skill,
        List<SkillVersionResponse> versions,
        List<SkillFileResponse> files,
        String documentationPath,
        String documentationContent,
        String downloadUrl,
        String activeVersion,
        AvatarChange avatarChange,
        VisibilityChange visibilityChange
) {
    public ReviewSkillDetailResponse(SkillDetailResponse skill, List<SkillVersionResponse> versions,
            List<SkillFileResponse> files, String documentationPath, String documentationContent,
            String downloadUrl, String activeVersion, AvatarChange avatarChange) {
        this(skill, versions, files, documentationPath, documentationContent, downloadUrl, activeVersion, avatarChange, null);
    }
    public record VisibilityChange(String previousVisibility, String requestedVisibility,
            String scanStatus, boolean readyForApproval) {}
    public record AvatarChange(Long candidateId, String currentAvatarUrl, String candidatePreviewUrl,
            String machineStatus, String reviewStatus, boolean readyForApproval, boolean removal) {}
    public ReviewSkillDetailResponse(SkillDetailResponse skill, List<SkillVersionResponse> versions,
            List<SkillFileResponse> files, String documentationPath, String documentationContent,
            String downloadUrl, String activeVersion) {
        this(skill, versions, files, documentationPath, documentationContent, downloadUrl, activeVersion, null);
    }
}
