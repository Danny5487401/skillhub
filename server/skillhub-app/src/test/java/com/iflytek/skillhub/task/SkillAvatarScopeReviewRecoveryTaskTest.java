package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import com.iflytek.skillhub.listener.SkillAvatarScopeReviewListener;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import java.time.Instant;

class SkillAvatarScopeReviewRecoveryTaskTest {
    @Test
    void scansOnlyUnreviewedExpandedScopesInBoundedIdOrder() {
        SkillJpaRepository skills = mock(SkillJpaRepository.class);
        SkillAvatarScopeReviewListener listener = mock(SkillAvatarScopeReviewListener.class);
        Skill skill = new Skill(10L, "foo", "owner", SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(skill, "id", 11L);
        when(skills.findScopeReviewRecoveryCandidates(eq(0L), eq(SkillStatus.ACTIVE), eq(SkillVisibility.PRIVATE),
                eq(SkillVisibility.NAMESPACE_ONLY), eq(SkillVisibility.PUBLIC), eq(SkillAvatarCandidate.Status.PENDING),
                eq(SkillAvatarCandidate.MachineStatus.PENDING), eq(SkillAvatarCandidate.MachineStatus.PROCESSING),
                eq(SkillAvatarCandidate.MachineStatus.ERROR), eq(3), eq(SkillAvatarCandidate.Status.REJECTED),
                any(Instant.class), any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(skill));

        new SkillAvatarScopeReviewRecoveryTask(skills, listener).recover();

        verify(listener).reviewScope(11L, null);
    }

    @Test
    void failedSkillDoesNotStarveLaterBatches() {
        SkillJpaRepository skills = mock(SkillJpaRepository.class);
        SkillAvatarScopeReviewListener listener = mock(SkillAvatarScopeReviewListener.class);
        Skill first = new Skill(10L, "first", "owner", SkillVisibility.PUBLIC);
        Skill second = new Skill(10L, "second", "owner", SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(first, "id", 11L);
        ReflectionTestUtils.setField(second, "id", 12L);
        when(skills.findScopeReviewRecoveryCandidates(eq(0L), eq(SkillStatus.ACTIVE), eq(SkillVisibility.PRIVATE),
                eq(SkillVisibility.NAMESPACE_ONLY), eq(SkillVisibility.PUBLIC), eq(SkillAvatarCandidate.Status.PENDING),
                eq(SkillAvatarCandidate.MachineStatus.PENDING), eq(SkillAvatarCandidate.MachineStatus.PROCESSING),
                eq(SkillAvatarCandidate.MachineStatus.ERROR), eq(3), eq(SkillAvatarCandidate.Status.REJECTED),
                any(Instant.class), any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(first));
        when(skills.findScopeReviewRecoveryCandidates(eq(11L), eq(SkillStatus.ACTIVE), eq(SkillVisibility.PRIVATE),
                eq(SkillVisibility.NAMESPACE_ONLY), eq(SkillVisibility.PUBLIC), eq(SkillAvatarCandidate.Status.PENDING),
                eq(SkillAvatarCandidate.MachineStatus.PENDING), eq(SkillAvatarCandidate.MachineStatus.PROCESSING),
                eq(SkillAvatarCandidate.MachineStatus.ERROR), eq(3), eq(SkillAvatarCandidate.Status.REJECTED),
                any(Instant.class), any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(second));
        doThrow(new IllegalStateException("storage unavailable")).when(listener).reviewScope(11L, null);
        SkillAvatarScopeReviewRecoveryTask task = new SkillAvatarScopeReviewRecoveryTask(skills, listener);

        org.assertj.core.api.Assertions.assertThatThrownBy(task::recover).isInstanceOf(IllegalStateException.class);
        task.recover();

        verify(listener).reviewScope(12L, null);
    }
}
