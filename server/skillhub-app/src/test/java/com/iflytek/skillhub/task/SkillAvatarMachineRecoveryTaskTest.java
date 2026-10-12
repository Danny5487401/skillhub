package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillAvatarAppService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillAvatarMachineRecoveryTaskTest {
    @Test
    void recoversAbandonedOrdinaryUploadAndContinuesAfterFailure() {
        SkillAvatarCandidateJpaRepository candidates = mock(SkillAvatarCandidateJpaRepository.class);
        SkillAvatarAppService avatars = mock(SkillAvatarAppService.class);
        SkillAvatarCandidate first = candidate(11L);
        SkillAvatarCandidate second = candidate(12L);
        when(candidates.findAbandonedMachineCandidates(eq(0L), eq(SkillAvatarCandidate.Status.PENDING),
                eq(SkillAvatarCandidate.MachineStatus.PENDING), eq(SkillAvatarCandidate.MachineStatus.PROCESSING),
                any(Instant.class), any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("secret-bearing failure"))
                .when(avatars).processMachine(eq(11L), any(AuditRequestContext.class));

        new SkillAvatarMachineRecoveryTask(candidates, avatars).recover();

        verify(avatars).processMachine(eq(11L), any(AuditRequestContext.class));
        verify(avatars).processMachine(eq(12L), any(AuditRequestContext.class));
    }

    private SkillAvatarCandidate candidate(Long id) {
        SkillAvatarCandidate candidate = new SkillAvatarCandidate(1L, "owner", "private.png", "sha",
                SkillVisibility.PUBLIC, true, false, "xfyun");
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
