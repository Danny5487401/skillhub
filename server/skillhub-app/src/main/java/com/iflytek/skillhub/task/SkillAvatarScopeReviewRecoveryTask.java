package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import com.iflytek.skillhub.listener.SkillAvatarScopeReviewListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

/** Recovers scope reviews lost after publication because private storage was unavailable. */
@Component
public class SkillAvatarScopeReviewRecoveryTask {
    private final SkillJpaRepository skills;
    private final SkillAvatarScopeReviewListener listener;
    private final AtomicLong lastScannedId = new AtomicLong();

    public SkillAvatarScopeReviewRecoveryTask(SkillJpaRepository skills, SkillAvatarScopeReviewListener listener) {
        this.skills = skills;
        this.listener = listener;
    }

    @Scheduled(fixedDelay = 300000)
    public void recover() {
        List<Skill> batch = findAfter(lastScannedId.get());
        if (batch.isEmpty() && lastScannedId.get() != 0L) {
            lastScannedId.set(0L);
            batch = findAfter(0L);
        }
        for (var skill : batch) {
            lastScannedId.set(skill.getId());
            listener.reviewScope(skill.getId(), null);
        }
    }

    private List<Skill> findAfter(Long afterId) {
        Instant now = Instant.now();
        return skills.findScopeReviewRecoveryCandidates(afterId, SkillStatus.ACTIVE, SkillVisibility.PRIVATE,
                SkillVisibility.NAMESPACE_ONLY, SkillVisibility.PUBLIC,
                SkillAvatarCandidate.Status.PENDING, SkillAvatarCandidate.MachineStatus.PENDING,
                SkillAvatarCandidate.MachineStatus.PROCESSING, SkillAvatarCandidate.MachineStatus.ERROR,
                3, SkillAvatarCandidate.Status.REJECTED, now.minusSeconds(600), now, PageRequest.of(0, 100));
    }
}
