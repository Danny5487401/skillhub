package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.service.GithubCredentialLeaseAppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "skillhub.github-sync.service-tokens="
        + "fixture-service-token-one-32-characters,fixture-service-token-two-32-characters")
class GithubSyncInternalControllerSecurityTest {
    private static final String CLAIM = "/internal/github-sync/v1/tasks/claim";
    private static final String REQUEST = """
            {"workerId":"fixture-worker","protocolMajor":1,"kind":"DISCOVERY"}
            """;

    @Autowired private MockMvc mockMvc;
    @MockBean private GithubCredentialLeaseAppService credentialLeases;
    @MockBean private GithubSyncLeaseService leases;

    @Test
    void anonymousAndWrongServiceTokensCannotClaim() throws Exception {
        mockMvc.perform(post(CLAIM).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        mockMvc.perform(post(CLAIM).header("Authorization", "Bearer wrong-service-token")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        mockMvc.perform(post(CLAIM).with(user("human-user"))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        verifyNoInteractions(credentialLeases);
    }

    @Test
    void bothConfiguredTokensReachProtocolButUnsupportedVersionIsRejected() throws Exception {
        when(credentialLeases.claim(com.iflytek.skillhub.githubsync.GithubSyncTaskKind.DISCOVERY,
                "fixture-worker")).thenReturn(Optional.empty());
        for (String token : new String[]{"fixture-service-token-one-32-characters",
                "fixture-service-token-two-32-characters"}) {
            mockMvc.perform(post(CLAIM).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post(CLAIM).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST.replace("\"protocolMajor\":1", "\"protocolMajor\":2")))
                    .andExpect(status().isUpgradeRequired())
                    .andExpect(jsonPath("$.code").value("PROTOCOL_UNSUPPORTED"));
        }
    }
}
