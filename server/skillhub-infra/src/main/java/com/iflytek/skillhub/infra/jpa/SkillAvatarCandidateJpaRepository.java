package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidateRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillAvatarCandidateJpaRepository extends JpaRepository<SkillAvatarCandidate, Long>, SkillAvatarCandidateRepository {
    List<SkillAvatarCandidate> findBySkillIdOrderByCreatedAtDesc(Long skillId);
    List<SkillAvatarCandidate> findByContentSha256AndMachineProviderAndMachineEnabledTrueAndManualEnabledAndMachineStatus(
            String contentSha256, String machineProvider, boolean manualEnabled,
            SkillAvatarCandidate.MachineStatus machineStatus);
    List<SkillAvatarCandidate> findByStatusAndReviewStatusOrderByCreatedAtAsc(
            SkillAvatarCandidate.Status status, SkillAvatarCandidate.ReviewStatus reviewStatus);
    List<SkillAvatarCandidate> findTop100ByDiscardAtBeforeAndObjectKeyIsNotNull(Instant before);
}
