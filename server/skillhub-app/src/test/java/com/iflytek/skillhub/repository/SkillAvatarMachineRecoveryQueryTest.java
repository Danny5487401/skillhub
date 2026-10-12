package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class SkillAvatarMachineRecoveryQueryTest {
    @Autowired private EntityManager entities;
    @Autowired private SkillAvatarCandidateJpaRepository candidates;

    @Test
    void selectsOnlyCurrentOrdinaryCandidatesAfterAbandonmentDeadline() {
        Namespace namespace = new Namespace("machine-recovery", "Machine recovery", "owner");
        entities.persist(namespace);
        SkillAvatarCandidate pending = candidate(namespace.getId(), "pending", false);
        SkillAvatarCandidate processing = candidate(namespace.getId(), "processing", true);
        SkillAvatarCandidate superseded = candidate(namespace.getId(), "superseded", false);
        SkillAvatarCandidate scoped = candidate(namespace.getId(), "scope", false);
        scoped.markScopeReviewVersion(null);
        Skill supersededSkill = entities.createQuery("SELECT s FROM Skill s WHERE s.id = :id", Skill.class)
                .setParameter("id", superseded.getSkillId()).getSingleResult();
        supersededSkill.setPendingAvatarId(null);
        entities.flush();
        entities.clear();

        Instant now = Instant.now();
        assertThat(recoverable(now)).isEmpty();
        assertThat(recoverable(now.plusSeconds(601))).extracting(SkillAvatarCandidate::getId)
                .containsExactly(pending.getId(), processing.getId());
        assertThat(recoverable(now.plusSeconds(601), pending.getId()))
                .extracting(SkillAvatarCandidate::getId).containsExactly(processing.getId());
    }

    private SkillAvatarCandidate candidate(Long namespaceId, String slug, boolean claim) {
        Skill skill = new Skill(namespaceId, slug, "owner", SkillVisibility.PUBLIC);
        entities.persist(skill);
        entities.flush();
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(skill.getId(), "owner", slug + ".png",
                "sha", SkillVisibility.PUBLIC, true, false, "xfyun");
        if (claim) {
            Instant now = Instant.now();
            candidate.claimMachineAttempt("attempt", now, now.plusSeconds(600));
        }
        entities.persist(candidate);
        entities.flush();
        skill.setPendingAvatarId(candidate.getId());
        entities.flush();
        return candidate;
    }

    private java.util.List<SkillAvatarCandidate> recoverable(Instant now) {
        return recoverable(now, 0L);
    }

    private java.util.List<SkillAvatarCandidate> recoverable(Instant now, Long afterId) {
        return candidates.findAbandonedMachineCandidates(afterId, SkillAvatarCandidate.Status.PENDING,
                SkillAvatarCandidate.MachineStatus.PENDING, SkillAvatarCandidate.MachineStatus.PROCESSING,
                now.minusSeconds(600), now, PageRequest.of(0, 100));
    }
}
