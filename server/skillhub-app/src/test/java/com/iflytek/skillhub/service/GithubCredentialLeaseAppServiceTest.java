package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.githubsync.GithubCredentialCipher;
import com.iflytek.skillhub.githubsync.GithubOfficialQuotaRepository;
import com.iflytek.skillhub.githubsync.GithubSystemCredential;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.githubsync.GithubSyncTaskKind;
import com.iflytek.skillhub.githubsync.GithubSyncTaskLease;
import com.iflytek.skillhub.githubsync.GithubSyncTaskMode;
import com.iflytek.skillhub.githubsync.GithubSystemCredentialRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubCredentialLeaseAppServiceTest {
    @Test
    void selectsQuotaResourceForEachTaskType() {
        assertResource(GithubSyncTaskKind.DISCOVERY, "SEARCH_RULE", "SEARCH");
        assertResource(GithubSyncTaskKind.DISCOVERY, "SEARCH_PREVIEW", "SEARCH");
        assertResource(GithubSyncTaskKind.DISCOVERY, "CURATED_OWNER", "CORE");
        assertResource(GithubSyncTaskKind.DISCOVERY, "CURATED_REPOSITORY", "CORE");
        assertResource(GithubSyncTaskKind.REPOSITORY_SYNC, "", "CORE");
    }

    @Test
    void configuredPoolUnavailableDefersWithCredentialReasonThenClearsAfterRecovery() {
        var leases = mock(GithubSyncLeaseService.class);
        var credentials = mock(GithubSystemCredentialRepository.class);
        var cipher = mock(GithubCredentialCipher.class);
        var quota = mock(GithubOfficialQuotaRepository.class);
        var service = new GithubCredentialLeaseAppService(leases, credentials, cipher, new ObjectMapper(), quota);
        var task = repositoryTask();
        when(leases.claim(GithubSyncTaskKind.REPOSITORY_SYNC, "worker"))
                .thenReturn(Optional.of(new GithubSyncLeaseService.LeasedTask(task, "fixture-lease")));
        when(credentials.hasConfiguredCredentials()).thenReturn(true);
        when(credentials.availableForClaim(any(), eq("CORE"))).thenReturn(Optional.empty());

        assertThat(service.claim(GithubSyncTaskKind.REPOSITORY_SYNC, "worker")).isEmpty();
        verify(quota).deferClaim(eq(task.taskId()), eq(1L), any(), any(), eq("CREDENTIAL_UNAVAILABLE"));
        verify(quota, never()).clearClaimWait(any(), org.mockito.ArgumentMatchers.anyLong());

        var credential = new GithubSystemCredential(UUID.randomUUID(), "fixture", "fingerprint", "v1",
                new byte[12], new byte[16], "ACTIVE", null, null, Instant.now(), Instant.now());
        when(credentials.availableForClaim(any(), eq("CORE"))).thenReturn(Optional.of(credential));
        when(cipher.decrypt(eq(credential.id()), eq("v1"), any(), any())).thenReturn("fixture-token");
        when(credentials.assignToLease(eq(task.taskId()), eq(1L), eq(credential.id()), eq("fingerprint"), any())).thenReturn(true);
        assertThat(service.claim(GithubSyncTaskKind.REPOSITORY_SYNC, "worker")).isPresent();
        verify(quota).clearClaimWait(task.taskId(), 1L);
    }

    @Test
    void removalOfConfiguredCredentialsAllowsAnonymousClaimAndClearsOldWait() {
        var leases = mock(GithubSyncLeaseService.class);
        var credentials = mock(GithubSystemCredentialRepository.class);
        var quota = mock(GithubOfficialQuotaRepository.class);
        var service = new GithubCredentialLeaseAppService(leases, credentials, mock(GithubCredentialCipher.class), new ObjectMapper(), quota);
        var task = repositoryTask();
        when(leases.claim(GithubSyncTaskKind.REPOSITORY_SYNC, "worker"))
                .thenReturn(Optional.of(new GithubSyncLeaseService.LeasedTask(task, "fixture-lease")));
        when(credentials.availableForClaim(any(), eq("CORE"))).thenReturn(Optional.empty());
        when(credentials.hasConfiguredCredentials()).thenReturn(false);
        assertThat(service.claim(GithubSyncTaskKind.REPOSITORY_SYNC, "worker")).isPresent();
        verify(quota).clearClaimWait(task.taskId(), 1L);
        verify(quota, never()).deferClaim(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    private GithubSyncTaskLease repositoryTask() {
        return new GithubSyncTaskLease(UUID.randomUUID(), UUID.randomUUID(), GithubSyncTaskKind.REPOSITORY_SYNC,
                GithubSyncTaskMode.FULL, 1, null, "{}", null, null, null, "[]", List.of(), List.of(), 1,
                Instant.now().plusSeconds(300));
    }

    private void assertResource(GithubSyncTaskKind kind, String entryType, String resource) {
        var leases = mock(GithubSyncLeaseService.class);
        var credentials = mock(GithubSystemCredentialRepository.class);
        var cipher = mock(GithubCredentialCipher.class);
        var service = new GithubCredentialLeaseAppService(leases, credentials, cipher,
                new ObjectMapper());
        var task = new GithubSyncTaskLease(UUID.randomUUID(), UUID.randomUUID(), kind,
                kind == GithubSyncTaskKind.REPOSITORY_SYNC ? GithubSyncTaskMode.FULL : null,
                1, null, "{\"entryType\":\"" + entryType + "\"}", null, null, null,
                "[]", List.of(), List.of(), 1, Instant.now().plusSeconds(300));
        when(leases.claim(eq(kind), eq("fixture-worker")))
                .thenReturn(Optional.of(new GithubSyncLeaseService.LeasedTask(task, "fixture-lease")));
        when(credentials.availableForClaim(any(Instant.class), eq(resource)))
                .thenReturn(Optional.empty());

        assertThat(service.claim(kind, "fixture-worker")).isPresent();
        verify(credentials).availableForClaim(any(Instant.class), eq(resource));
    }
}
