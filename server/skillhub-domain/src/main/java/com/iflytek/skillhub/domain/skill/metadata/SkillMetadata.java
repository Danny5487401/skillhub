package com.iflytek.skillhub.domain.skill.metadata;

import java.util.Map;

public record SkillMetadata(
    String name,
    String description,
    String version,
    String body,
    Map<String, Object> frontmatter
) {
    /** Human-readable label; unsupported legacy snapshot values fall back to the package name. */
    public String displayName() {
        return resolveDisplayName(name, frontmatter);
    }

    private static String resolveDisplayName(String name, Map<String, Object> frontmatter) {
        if (frontmatter == null || !(frontmatter.get("metadata") instanceof Map<?, ?> metadata)) {
            return name;
        }
        Object value = metadata.get("display-name");
        if (value == null) {
            return name;
        }
        if (!(value instanceof String text)) {
            return name;
        }
        String displayName = text.strip();
        if (displayName.isEmpty()) {
            return name;
        }
        if (displayName.length() > 200) {
            return name;
        }
        return displayName;
    }
}
