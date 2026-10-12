package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import org.junit.jupiter.api.Test;

class SkillAvatarUrlsTest {
    @Test
    void hidesAvatarWhenCurrentVisibilityExceedsApprovedAudience() {
        assertThat(SkillAvatarUrls.current(1L, SkillVisibility.PRIVATE, SkillVisibility.NAMESPACE_ONLY,
                "team", "foo")).isNull();
        assertThat(SkillAvatarUrls.current(1L, SkillVisibility.NAMESPACE_ONLY, SkillVisibility.PUBLIC,
                "team", "foo")).isNull();
        assertThat(SkillAvatarUrls.current(1L, SkillVisibility.PUBLIC, SkillVisibility.PUBLIC,
                "global", "foo")).isEqualTo("/api/v1/skills/global/foo/avatar");
        assertThat(SkillAvatarUrls.current(null, SkillVisibility.PUBLIC, SkillVisibility.PUBLIC,
                "global", "foo")).isNull();
    }
}
