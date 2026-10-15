package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillAvatarUploadJpaRepository;
import org.springframework.data.domain.PageRequest;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SkillAvatarCleanupTask {
    private final SkillAvatarCandidateJpaRepository candidates;
    private final ObjectStorageService storage;
    private final SkillAvatarUploadJpaRepository uploads;

    public SkillAvatarCleanupTask(SkillAvatarCandidateJpaRepository candidates, ObjectStorageService storage, SkillAvatarUploadJpaRepository uploads) {
        this.candidates = candidates;
        this.storage = storage;
        this.uploads = uploads;
    }

    @Scheduled(cron = "0 35 2 * * ?")
    @Transactional
    public void removeExpiredImages() {
        for (var upload : uploads.findExpired(Instant.now(), PageRequest.of(0, 100))) {
            storage.deleteObject(upload.getObjectKey());
            upload.clearObjectKey();
        }
        for (SkillAvatarCandidate candidate : candidates.findTop100ByDiscardAtBeforeAndObjectKeyIsNotNull(Instant.now())) {
            storage.deleteObject(candidate.getObjectKey());
            candidate.clearObjectKey();
        }
    }
}
