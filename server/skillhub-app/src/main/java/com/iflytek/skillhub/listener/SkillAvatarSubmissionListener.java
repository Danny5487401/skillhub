package com.iflytek.skillhub.listener;

import com.iflytek.skillhub.service.SkillAvatarAppService;
import com.iflytek.skillhub.service.SkillAvatarUploadService.AvatarSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SkillAvatarSubmissionListener {
    private static final Logger log = LoggerFactory.getLogger(SkillAvatarSubmissionListener.class);
    private final SkillAvatarAppService avatars;
    public SkillAvatarSubmissionListener(SkillAvatarAppService avatars) { this.avatars = avatars; }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void submitted(AvatarSubmittedEvent event) {
        try {
            avatars.processMachine(event.candidateId(), event.context());
        } catch (Exception failure) {
            log.warn("Avatar submission moderation failed for candidate {}, failureType={}",
                    event.candidateId(), failure.getClass().getSimpleName());
        }
    }
}
