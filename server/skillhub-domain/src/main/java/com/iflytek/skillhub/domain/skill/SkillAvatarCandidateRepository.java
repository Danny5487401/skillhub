package com.iflytek.skillhub.domain.skill;

import java.util.List;

public interface SkillAvatarCandidateRepository {
    List<SkillAvatarCandidate> findBySkillIdOrderByCreatedAtDesc(Long skillId);
}
