package com.iflytek.skillhub.listener;

import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.dto.SkillAvatarStatusResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillAvatarAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SkillAvatarScopeReviewListener {
    private static final Logger log = LoggerFactory.getLogger(SkillAvatarScopeReviewListener.class);
    private final SkillAvatarAppService avatars;

    public SkillAvatarScopeReviewListener(SkillAvatarAppService avatars) {
        this.avatars = avatars;
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSkillPublished(SkillPublishedEvent event) {
        reviewScope(event.skillId(), event.publisherId());
    }

    public void reviewScope(Long skillId, String publisherId) {
        try {
            SkillAvatarStatusResponse candidate = avatars.prepareScopeReview(skillId, publisherId);
            if (candidate != null && ("PENDING".equals(candidate.machineStatus())
                    || "PROCESSING".equals(candidate.machineStatus())
                    || "ERROR".equals(candidate.machineStatus()))) {
                avatars.processMachine(candidate.candidateId(), new AuditRequestContext(null, null));
            }
        } catch (Exception failure) {
            // Provider errors can include credential-bearing request URLs. Never log their message or stack here.
            log.warn("Avatar scope review failed for skill {}, failureType={}",
                    skillId, failure.getClass().getSimpleName());
        }
    }
}
