package com.iflytek.skillhub.domain.skill.metadata;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.util.Map;

public record SkillMetadata(
    String name,
    String description,
    String version,
    String body,
    Map<String, Object> frontmatter,
    String displayNameOverride
) {
    public SkillMetadata(String name, String description, String version, String body, Map<String, Object> frontmatter) {
        this(name, description, version, body, frontmatter, null);
    }

    /** Explicit portal input is validated before it becomes part of the version snapshot. */
    public SkillMetadata withDisplayNameOverride(String value) {
        String override = value == null ? null : value.strip();
        if (override != null && override.length() > 200) {
            throw new DomainBadRequestException(
                    "error.skill.displayName.tooLong", 200);
        }
        return new SkillMetadata(name, description, version, body, frontmatter,
                override == null || override.isEmpty() ? null : override);
    }

    /** Human-readable label; unsupported legacy snapshot values fall back to the package name. */
    public String displayName() {
        if (displayNameOverride != null && !displayNameOverride.isBlank()
                && displayNameOverride.strip().length() <= 200) {
            return displayNameOverride.strip();
        }
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
