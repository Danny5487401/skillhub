package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SkillAvatarCleanupTask {
    private final SkillAvatarCandidateJpaRepository candidates;
    private final ObjectStorageService storage;

    public SkillAvatarCleanupTask(SkillAvatarCandidateJpaRepository candidates, ObjectStorageService storage) {
        this.candidates = candidates;
        this.storage = storage;
    }

    @Scheduled(cron = "0 35 2 * * ?")
    @Transactional
    public void removeExpiredImages() {
        for (SkillAvatarCandidate candidate : candidates.findTop100ByDiscardAtBeforeAndObjectKeyIsNotNull(Instant.now())) {
            storage.deleteObject(candidate.getObjectKey());
            candidate.clearObjectKey();
        }
    }
}
