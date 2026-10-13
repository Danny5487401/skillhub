package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidateRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillAvatarCandidateJpaRepository extends JpaRepository<SkillAvatarCandidate, Long>, SkillAvatarCandidateRepository {
    List<SkillAvatarCandidate> findBySkillIdOrderByCreatedAtDesc(Long skillId);
    boolean existsBySkillIdAndAudienceAndScopeReviewVersionIdAndStatus(Long skillId,
            SkillVisibility audience, Long versionId,
            SkillAvatarCandidate.Status status);
    List<SkillAvatarCandidate> findByStatusAndReviewStatusOrderByCreatedAtAsc(
            SkillAvatarCandidate.Status status, SkillAvatarCandidate.ReviewStatus reviewStatus);
    List<SkillAvatarCandidate> findTop100ByDiscardAtBeforeAndObjectKeyIsNotNull(Instant before);

    @Query("""
            SELECT c FROM SkillAvatarCandidate c, Skill s
            WHERE c.id > :afterId AND s.id = c.skillId AND s.pendingAvatarId = c.id
              AND c.scopeReview = false AND c.status = :pendingStatus
              AND ((c.machineStatus = :pendingMachine AND c.createdAt <= :staleBefore)
                OR (c.machineStatus = :processingMachine AND c.machineLeaseUntil <= :now))
            ORDER BY c.id ASC
            """)
    List<SkillAvatarCandidate> findAbandonedMachineCandidates(@Param("afterId") Long afterId,
            @Param("pendingStatus") SkillAvatarCandidate.Status pendingStatus,
            @Param("pendingMachine") SkillAvatarCandidate.MachineStatus pendingMachine,
            @Param("processingMachine") SkillAvatarCandidate.MachineStatus processingMachine,
            @Param("staleBefore") Instant staleBefore, @Param("now") Instant now, Pageable pageable);
}
