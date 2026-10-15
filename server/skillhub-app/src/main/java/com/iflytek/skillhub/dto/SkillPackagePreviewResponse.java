package com.iflytek.skillhub.dto;

/** Package defaults only: preview never creates a skill or changes its files. */
public record SkillPackagePreviewResponse(String name, String displayName, String description, String version) {}
