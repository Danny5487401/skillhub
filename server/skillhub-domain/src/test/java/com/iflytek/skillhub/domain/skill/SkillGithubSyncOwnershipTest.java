package com.iflytek.skillhub.domain.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillGithubSyncOwnershipTest {
    @Test
    void transfersTechnicalOwnershipOnlyToTheReservedSystemActor() {
        Skill skill = new Skill(10L, "demo", "original-owner", SkillVisibility.NAMESPACE_ONLY);

        assertThrows(IllegalArgumentException.class, () -> skill.assignGithubSyncOwnership("admin"));
        assertEquals("original-owner", skill.getOwnerId());
        assertFalse(skill.isGithubSyncManaged());

        skill.assignGithubSyncOwnership("github-sync-publisher");
        assertEquals("github-sync-publisher", skill.getOwnerId());
        assertTrue(skill.isGithubSyncManaged());
    }
}
