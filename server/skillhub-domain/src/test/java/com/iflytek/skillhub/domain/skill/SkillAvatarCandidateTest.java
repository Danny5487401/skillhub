package com.iflytek.skillhub.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillAvatarCandidateTest {
    @Test
    void supersededCandidateCannotBeActivatedByLateMachineResult() {
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(10L, "owner", "avatars/one", "abc",
                SkillVisibility.PUBLIC, true, false, "xfyun");
        candidate.supersede();

        assertThat(candidate.getStatus()).isEqualTo(SkillAvatarCandidate.Status.SUPERSEDED);
        assertThat(candidate.getDiscardAt()).isNotNull();
        assertThatThrownBy(() -> candidate.machineResult(SkillAvatarCandidate.MachineStatus.PASSED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(candidate::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blockedMachineOnlyCandidateCanRequestManualAppeal() {
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(10L, "owner", "avatars/one", "abc",
                SkillVisibility.PUBLIC, true, false, "aliyun");
        candidate.machineResult(SkillAvatarCandidate.MachineStatus.BLOCKED);
        candidate.appeal();
        candidate.approve("reviewer", "false positive");
        candidate.activate();

        assertThat(candidate.getStatus()).isEqualTo(SkillAvatarCandidate.Status.ACTIVE);
        assertThat(candidate.getReviewedBy()).isEqualTo("reviewer");
    }
}
