package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SkillAvatarUrls {
    private SkillAvatarUrls() {}

    public static String current(Skill skill, String namespaceSlug) {
        return current(skill.getActiveAvatarId(), skill.getActiveAvatarAudience(), skill.getVisibility(),
                namespaceSlug, skill.getSlug());
    }

    public static String current(Long activeAvatarId, SkillVisibility approved, SkillVisibility current,
                                 String namespaceSlug, String skillSlug) {
        if (activeAvatarId == null || approved == null) {
            return null;
        }
        if (current == SkillVisibility.PUBLIC && approved != SkillVisibility.PUBLIC
                || current == SkillVisibility.NAMESPACE_ONLY && approved == SkillVisibility.PRIVATE) {
            return null;
        }
        return "/api/v1/skills/" + segment(namespaceSlug) + "/" + segment(skillSlug) + "/avatar";
    }

    public static String candidate(String namespaceSlug, Skill skill, long candidateId) {
        return "/api/v1/skills/" + segment(namespaceSlug) + "/" + segment(skill.getSlug())
                + "/avatar/candidates/" + candidateId + "/image";
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
