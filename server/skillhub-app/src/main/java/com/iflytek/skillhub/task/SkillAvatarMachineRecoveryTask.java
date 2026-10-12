package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillAvatarAppService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers ordinary uploads abandoned before or during machine moderation. */
@Component
public class SkillAvatarMachineRecoveryTask {
    private static final Logger log = LoggerFactory.getLogger(SkillAvatarMachineRecoveryTask.class);
    private final SkillAvatarCandidateJpaRepository candidates;
    private final SkillAvatarAppService avatars;
    private final AtomicLong lastScannedId = new AtomicLong();

    public SkillAvatarMachineRecoveryTask(SkillAvatarCandidateJpaRepository candidates, SkillAvatarAppService avatars) {
        this.candidates = candidates;
        this.avatars = avatars;
    }

    @Scheduled(fixedDelay = 300000)
    public void recover() {
        List<SkillAvatarCandidate> batch = findAfter(lastScannedId.get());
        if (batch.isEmpty() && lastScannedId.get() != 0L) {
            lastScannedId.set(0L);
            batch = findAfter(0L);
        }
        for (SkillAvatarCandidate candidate : batch) {
            lastScannedId.set(candidate.getId());
            try {
                avatars.processMachine(candidate.getId(), new AuditRequestContext(null, null));
            } catch (Exception failure) {
                // Provider exceptions may contain credential-bearing URLs; log no message or stack.
                log.warn("Avatar machine recovery failed for candidate {}, failureType={}",
                        candidate.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    private List<SkillAvatarCandidate> findAfter(Long afterId) {
        Instant now = Instant.now();
        return candidates.findAbandonedMachineCandidates(afterId, SkillAvatarCandidate.Status.PENDING,
                SkillAvatarCandidate.MachineStatus.PENDING, SkillAvatarCandidate.MachineStatus.PROCESSING,
                now.minusSeconds(600), now, PageRequest.of(0, 100));
    }
}
