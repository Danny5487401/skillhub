package com.iflytek.skillhub.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.time.Instant;

class SkillAvatarCandidateTest {
    @Test
    void initialStatesCoverFourReviewPolicies() {
        for (boolean machine : new boolean[] { false, true }) {
            for (boolean manual : new boolean[] { false, true }) {
                SkillAvatarCandidate candidate = new SkillAvatarCandidate(10L, "owner", "avatars/one", "abc",
                        SkillVisibility.PUBLIC, machine, manual, machine ? "xfyun" : null);
                assertThat(candidate.getMachineStatus()).isEqualTo(machine
                        ? SkillAvatarCandidate.MachineStatus.PENDING : SkillAvatarCandidate.MachineStatus.SKIPPED);
                assertThat(candidate.getReviewStatus()).isEqualTo(manual
                        ? SkillAvatarCandidate.ReviewStatus.PENDING : SkillAvatarCandidate.ReviewStatus.NOT_REQUIRED);
            }
        }
    }

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

    @Test
    void errorEnforcesOneHourCooldownBeforeManualOrAutomaticRetry() {
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(10L, "owner", "avatars/one", "abc",
                SkillVisibility.PUBLIC, true, false, "xfyun");
        Instant now = Instant.now();
        assertThat(candidate.claimMachineAttempt("first", now, now.plusSeconds(600))).isTrue();
        assertThat(candidate.completeMachineAttempt("first", SkillAvatarCandidate.MachineStatus.ERROR)).isTrue();
        assertThat(candidate.getMachineAttemptCount()).isEqualTo(1);
        assertThat(candidate.canRetryMachine(now.plusSeconds(3599))).isFalse();
        assertThat(candidate.claimMachineAttempt("too-early", now.plusSeconds(3599), now.plusSeconds(4199)))
                .isFalse();
        Instant due = candidate.getMachineNextRetryAt();
        assertThat(candidate.canRetryMachine(due)).isTrue();
        assertThat(candidate.claimMachineAttempt("second", due, due.plusSeconds(600))).isTrue();
        assertThat(candidate.getMachineAttemptCount()).isEqualTo(2);
    }
}
