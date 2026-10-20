package com.iflytek.skillhub.service;

import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.*;
import com.iflytek.skillhub.dto.GithubSyncWorkerStatusResponse;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfigurationRepository;
import com.iflytek.skillhub.repository.GithubDeepDiscoveryQueryRepository;
import com.iflytek.skillhub.repository.GithubSyncWorkerPresenceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubDeepDiscoveryCredentialWaitTest {
    @Test
    void pacingWaitDoesNotStopInFlightDiscoveryButWorkerOutageDoes() {
        var queries=mock(GithubDeepDiscoveryQueryRepository.class);
        var configurations=mock(GithubDeepDiscoveryConfigurationRepository.class);
        var workers=mock(GithubSyncWorkerPresenceRepository.class);
        var service=new GithubDeepDiscoveryReadAppService(queries,configurations,workers);
        Instant now=Instant.now();
        when(configurations.get()).thenReturn(new GithubDeepDiscoveryConfiguration(1,1,true,
                GithubDeepDiscoveryConfiguration.BudgetProfile.BALANCED,List.of(),"fixture",now,null));
        when(workers.status()).thenReturn(new GithubSyncWorkerStatusResponse(now,true));
        when(queries.compatibleWorker(Channel.REPOSITORY_METADATA)).thenReturn(true);
        when(queries.compatibleWorker(Channel.OWNER_EXPANSION)).thenReturn(true);
        when(queries.discoveryBudgetWait()).thenReturn(new GithubDeepDiscoveryQueryRepository.BudgetWait(true,now.plusSeconds(200)));
        when(queries.progressingWork(1,null,null)).thenReturn(List.of("NEW_DISCOVERY","SOURCE_UPDATE"));
        assertThat(service.summary(now.minusSeconds(3600),now.minusSeconds(1)).waits()).anySatisfy(wait -> {
            assertThat(wait.reason()).isEqualTo("BUDGET");
            assertThat(wait.continuingWork()).contains("NEW_DISCOVERY","SOURCE_UPDATE");
        });
        when(workers.status()).thenReturn(new GithubSyncWorkerStatusResponse(now,false));
        assertThat(service.summary(now.minusSeconds(3600),now.minusSeconds(1)).waits()).allSatisfy(wait -> assertThat(wait.continuingWork()).isEmpty());
    }

    @Test
    void currentPoolStateOverridesStaleTaskReasonBeforeAnotherClaim() {
        var queries = mock(GithubDeepDiscoveryQueryRepository.class);
        var configurations = mock(GithubDeepDiscoveryConfigurationRepository.class);
        var workers = mock(GithubSyncWorkerPresenceRepository.class);
        var service = new GithubDeepDiscoveryReadAppService(queries, configurations, workers);
        Instant now = Instant.now();
        when(configurations.get()).thenReturn(new GithubDeepDiscoveryConfiguration(1, 1, true,
                GithubDeepDiscoveryConfiguration.BudgetProfile.BALANCED, List.of(), "fixture", now, null));
        when(workers.status()).thenReturn(new GithubSyncWorkerStatusResponse(now, true));
        var repository = new RepositorySummary(99, "fixture", "repo", now, now,
                RepositoryStatus.PENDING, List.of(Channel.README), null, false, null, 3);
        when(queries.detail(1, 99)).thenReturn(new RepositoryDetail(repository, null, now,
                "NOT_CHECKED", List.of(), null, null));
        when(queries.repositoryWaits(1, 99L)).thenReturn(List.of(new GithubDeepDiscoveryQueryRepository.WaitEvidence(
                "CREDENTIAL_UNAVAILABLE", "CANDIDATE_VALIDATION", now.plusSeconds(60))));
        when(queries.credentialPoolUnavailable("CORE")).thenReturn(true);

        var blocked = service.repository(99);
        assertThat(blocked.waits()).anySatisfy(wait -> {
            assertThat(wait.reason()).isEqualTo("CREDENTIAL_UNAVAILABLE");
            assertThat(wait.action()).isEqualTo("MANAGE_CREDENTIALS");
            assertThat(wait.retryAt()).isNull();
        });
        assertThat(blocked.waits()).noneMatch(wait -> wait.reason().equals("PRIMARY_RATE_LIMIT"));

        when(queries.credentialPoolUnavailable("CORE")).thenReturn(false);
        assertThat(service.repository(99).waits()).noneMatch(wait -> wait.reason().equals("CREDENTIAL_UNAVAILABLE"));
    }
}
