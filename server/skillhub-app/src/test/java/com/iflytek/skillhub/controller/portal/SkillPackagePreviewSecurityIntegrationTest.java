package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.dto.SkillPackagePreviewResponse;
import com.iflytek.skillhub.service.SkillPackagePreviewService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises real ordered filter chains, scope parsing, and stored browser sessions.
 * Token lifecycle verification is stubbed; these tests focus on routing and access control.
 */
@SpringBootTest(properties = "skillhub.ratelimit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillPackagePreviewSecurityIntegrationTest {
    @Autowired MockMvc mockMvc;
    @MockBean ApiTokenService tokens;
    @Autowired UserAccountRepository users;
    @MockBean SkillPackagePreviewService previews;
    private String userId;

    @BeforeEach
    void setUp() {
        userId = "preview-security-" + UUID.randomUUID();
        users.save(new UserAccount(userId, "Preview user", userId + "@example.test", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void browserSessionWithCsrfCanPreview(String prefix) throws Exception {
        given(previews.preview(anyList())).willReturn(new SkillPackagePreviewResponse("foo", "中文标题", "Summary", "1.0.0"));
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive())
                        .session(session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("中文标题"));
        verify(previews).preview(anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void publishScopedTokenCanPreviewWithoutSessionOrCsrf(String prefix) throws Exception {
        given(previews.preview(anyList())).willReturn(new SkillPackagePreviewResponse("foo", "中文标题", "Summary", "1.0.0"));
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive())
                        .header("Authorization", "Bearer " + token("skill:publish")))
                .andExpect(status().isOk());
        verify(previews).preview(anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void missingPublishScopeIsRejectedBeforeParsing(String prefix) throws Exception {
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive())
                        .header("Authorization", "Bearer " + token("skill:read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(previews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void anonymousIsRejectedBeforeParsing(String prefix) throws Exception {
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive()).with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(previews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void browserSessionWithoutCsrfIsRejectedBeforeParsing(String prefix) throws Exception {
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive()).session(session()))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(previews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void examplesSummaryUsesOptionalCountAndCompatibleStatusValues(String prefix) throws Exception {
        given(previews.preview(anyList())).willReturn(new SkillPackagePreviewResponse("foo", "Foo", "Summary", "1.0.0",
                com.iflytek.skillhub.domain.skill.validation.SkillExamplesSummary.Status.ready, 2));
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive()).session(session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.examplesStatus").value("ready"))
                .andExpect(jsonPath("$.data.examplesCount").value(2));
        given(previews.preview(anyList())).willReturn(new SkillPackagePreviewResponse("foo", "Foo", "Summary", "1.0.0",
                com.iflytek.skillhub.domain.skill.validation.SkillExamplesSummary.Status.invalid, null));
        mockMvc.perform(multipart(prefix + "/skills/package-preview").file(archive()).session(session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.examplesStatus").value("invalid"))
                .andExpect(jsonPath("$.data.examplesCount").doesNotExist());
    }

    private MockHttpSession session() {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new PlatformPrincipal(userId, "Preview user", userId + "@example.test", "", "local", Set.of());
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private String token(String scope) {
        String rawToken = "preview-test-" + UUID.randomUUID();
        var token = new com.iflytek.skillhub.auth.entity.ApiToken(userId, "preview", "preview", "test-hash",
                "[\"" + scope + "\"]");
        given(tokens.validateToken(rawToken)).willReturn(java.util.Optional.of(token));
        return rawToken;
    }

    private MockMultipartFile archive() throws Exception {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("---\nname: foo\ndescription: Summary\n---\nBody".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return new MockMultipartFile("file", "skill.zip", "application/zip", output.toByteArray());
        }
    }
}
