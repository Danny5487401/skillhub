package com.iflytek.skillhub.service;

import com.iflytek.skillhub.githubsync.GithubSyncFailureReason;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.githubsync.GithubSyncSchedulePolicyRepository;
import com.iflytek.skillhub.githubsync.GithubSyncTaskRepository;
import com.iflytek.skillhub.githubsync.GithubSystemCredentialRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GithubSyncLeaseFailureDetailTest {
    private final GithubSyncTaskRepository tasks = mock(GithubSyncTaskRepository.class);
    private final GithubSyncLeaseService leases = new GithubSyncLeaseService(tasks,
            mock(GithubSystemCredentialRepository.class),
            mock(GithubSyncSchedulePolicyRepository.class), Duration.ofMinutes(5), Duration.ofHours(24));
    private final UUID taskId = UUID.randomUUID();
    private final String token = "a".repeat(64);

    @Test
    void rejectsArbitraryDiagnosticTextBeforeItReachesTaskPersistence() {
        assertThatThrownBy(() -> leases.fail(taskId, 1, token,
                GithubSyncFailureReason.RETRYABLE_ACQUISITION, "SYNTHETIC_SECRET_123", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("detail must be a supported diagnostic code");
        verifyNoInteractions(tasks);
    }

    @Test
    void acceptsOnlyWorkerAndKnownGithubDiagnostics() {
        leases.fail(taskId, 1, token, GithubSyncFailureReason.RETRYABLE_ACQUISITION,
                "WORKER_ERROR", null, null);
        leases.fail(taskId, 1, token, GithubSyncFailureReason.RETRYABLE_ACQUISITION,
                "GITHUB_HTTP_403:GITHUB_RATE_LIMITED", null, null);

        verify(tasks).fail(eq(taskId), eq(1L), anyString(),
                eq(GithubSyncFailureReason.RETRYABLE_ACQUISITION), eq("WORKER_ERROR"), isNull(), any());
        verify(tasks).fail(eq(taskId), eq(1L), anyString(),
                eq(GithubSyncFailureReason.RETRYABLE_ACQUISITION),
                eq("GITHUB_HTTP_403:GITHUB_RATE_LIMITED"), isNull(), any());
    }
}
