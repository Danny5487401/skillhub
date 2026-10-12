package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class SkillAvatarScopeRecoveryQueryTest {
    @Autowired private TestEntityManager entities;
    @Autowired private SkillJpaRepository skills;

    @Test
    void selectsMissingAndErroredReviewsButSkipsCurrentRejectedCandidate() {
        Namespace namespace = entities.persistAndFlush(new Namespace("scope-recovery", "Scope recovery", "owner"));
        Skill needsReview = expandedSkill(namespace.getId(), "needs-review");
        Skill rejected = expandedSkill(namespace.getId(), "rejected");
        Skill oldRejection = expandedSkill(namespace.getId(), "old-rejection");
        Skill retry = expandedSkill(namespace.getId(), "retry");
        Skill fresh = expandedSkill(namespace.getId(), "fresh");
        Skill processing = expandedSkill(namespace.getId(), "processing");
        SkillAvatarCandidate rejectedCandidate = new SkillAvatarCandidate(rejected.getId(), "owner", "old/rejected.png",
                "sha", SkillVisibility.PUBLIC, false, true, null);
        rejectedCandidate.markScopeReviewVersion(4L);
        rejectedCandidate.reject("reviewer", "blocked");
        entities.persistAndFlush(rejectedCandidate);
        SkillAvatarCandidate historical = new SkillAvatarCandidate(oldRejection.getId(), "owner", "old/historical.png",
                "sha", SkillVisibility.PUBLIC, false, true, null);
        historical.markScopeReviewVersion(3L);
        historical.reject("reviewer", "blocked on older publication");
        entities.persistAndFlush(historical);
        SkillAvatarCandidate errored = new SkillAvatarCandidate(retry.getId(), "owner", "old/error.png", "sha",
                SkillVisibility.PUBLIC, true, false, "xfyun");
        errored.machineResult(SkillAvatarCandidate.MachineStatus.ERROR);
        entities.persistAndFlush(errored);
        retry.setPendingAvatarId(errored.getId());
        entities.persistAndFlush(retry);
        SkillAvatarCandidate freshCandidate = new SkillAvatarCandidate(fresh.getId(), "owner", "old/fresh.png", "sha",
                SkillVisibility.PUBLIC, true, false, "xfyun");
        entities.persistAndFlush(freshCandidate);
        fresh.setPendingAvatarId(freshCandidate.getId());
        entities.persistAndFlush(fresh);
        SkillAvatarCandidate processingCandidate = new SkillAvatarCandidate(processing.getId(), "owner",
                "old/processing.png", "sha", SkillVisibility.PUBLIC, true, false, "xfyun");
        Instant claimTime = Instant.now();
        processingCandidate.claimMachineAttempt("first-attempt", claimTime, claimTime.plusSeconds(600));
        entities.persistAndFlush(processingCandidate);
        processing.setPendingAvatarId(processingCandidate.getId());
        entities.persistAndFlush(processing);
        entities.clear();

        Instant now = Instant.now();
        var results = recoverable(now);

        assertThat(results).extracting(Skill::getId)
                .containsExactly(needsReview.getId(), oldRejection.getId(), retry.getId());
        assertThat(recoverable(now.plusSeconds(601))).extracting(Skill::getId)
                .containsExactly(needsReview.getId(), oldRejection.getId(), retry.getId(),
                        fresh.getId(), processing.getId());
    }

    @Test
    void cooledDownErrorIsNotRetriedOnConsecutiveTicksAndStopsAfterThreeAttempts() {
        Namespace namespace = entities.persistAndFlush(new Namespace("scope-cooldown", "Scope cooldown", "owner"));
        Skill cooling = expandedSkill(namespace.getId(), "cooling");
        Skill exhausted = expandedSkill(namespace.getId(), "exhausted");
        SkillAvatarCandidate first = errorCandidate(cooling, 1);
        SkillAvatarCandidate third = errorCandidate(exhausted, 3);
        cooling.setPendingAvatarId(first.getId());
        exhausted.setPendingAvatarId(third.getId());
        entities.persistAndFlush(cooling);
        entities.persistAndFlush(exhausted);
        entities.clear();

        Instant now = Instant.now();
        assertThat(recoverable(now)).isEmpty();
        assertThat(recoverable(now.plusSeconds(300))).isEmpty();
        assertThat(recoverable(now.plusSeconds(3601))).extracting(Skill::getId)
                .containsExactly(cooling.getId());
    }

    private SkillAvatarCandidate errorCandidate(Skill skill, int attempts) {
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(skill.getId(), "owner", "old/error.png",
                "sha", SkillVisibility.PUBLIC, true, false, "xfyun");
        for (int n = 0; n < attempts; n++) {
            Instant at = Instant.now().plusSeconds(n * 3601L);
            candidate.claimMachineAttempt("attempt-" + n, at, at.plusSeconds(600));
            candidate.completeMachineAttempt("attempt-" + n, SkillAvatarCandidate.MachineStatus.ERROR);
        }
        return entities.persistAndFlush(candidate);
    }

    private java.util.List<Skill> recoverable(Instant now) {
        return skills.findScopeReviewRecoveryCandidates(0L, SkillStatus.ACTIVE,
                SkillVisibility.PRIVATE, SkillVisibility.NAMESPACE_ONLY, SkillVisibility.PUBLIC,
                SkillAvatarCandidate.Status.PENDING, SkillAvatarCandidate.MachineStatus.PENDING,
                SkillAvatarCandidate.MachineStatus.PROCESSING, SkillAvatarCandidate.MachineStatus.ERROR, 3,
                SkillAvatarCandidate.Status.REJECTED, now.minusSeconds(600), now, PageRequest.of(0, 100));
    }

    private Skill expandedSkill(Long namespaceId, String slug) {
        Skill skill = entities.persistAndFlush(new Skill(namespaceId, slug, "owner", SkillVisibility.PRIVATE));
        SkillAvatarCandidate active = new SkillAvatarCandidate(skill.getId(), "owner", "old/" + slug + ".png",
                "sha", SkillVisibility.PRIVATE, false, false, null);
        active.activate();
        entities.persistAndFlush(active);
        skill.activateAvatar(active.getId(), SkillVisibility.PRIVATE);
        skill.setLatestVersionId(4L);
        skill.setVisibility(SkillVisibility.PUBLIC);
        entities.persistAndFlush(skill);
        return skill;
    }
}
