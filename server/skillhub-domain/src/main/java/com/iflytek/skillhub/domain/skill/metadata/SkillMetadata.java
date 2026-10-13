package com.iflytek.skillhub.domain.skill.metadata;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.util.Map;

public record SkillMetadata(
    String name,
    String description,
    String version,
    String body,
    Map<String, Object> frontmatter
) {
    public SkillMetadata {
        resolveDisplayName(name, frontmatter);
    }

    /** Human-readable label; the package name remains the source of the skill slug. */
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
            throw new DomainBadRequestException("error.skill.metadata.displayName.invalid");
        }
        String displayName = text.strip();
        if (displayName.isEmpty()) {
            return name;
        }
        if (displayName.length() > 200) {
            throw new DomainBadRequestException("error.skill.metadata.displayName.tooLong", 200);
        }
        return displayName;
    }
}
