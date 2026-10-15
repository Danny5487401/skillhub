package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.social.SkillRatingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillRatingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SkillRatingService skillRatingService;

    @MockBean
    private NamespaceMemberRepository namespaceMemberRepository;

    @MockBean
    private SkillRepository skills;

    @BeforeEach
    void targetExists() {
        Skill skill = new Skill(1L, "restricted-target", "owner", SkillVisibility.PRIVATE);
        skill.setLatestVersionId(11L);
        when(skills.findById(10L)).thenReturn(Optional.of(skill));
    }

    @Test
    void rate_skill_returns_envelope() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
                "user-42",
                "tester",
                "tester@example.com",
                "https://example.com/avatar.png",
                "github",
                Set.of("SUPER_ADMIN")
        );
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );

        mockMvc.perform(put("/api/v1/skills/10/rating")
                        .with(authentication(auth))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verify(skillRatingService).rate(eq(10L), eq("user-42"), eq((short) 4));
    }

    @Test
    void get_user_rating_returns_score() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
                "user-42",
                "tester",
                "tester@example.com",
                "https://example.com/avatar.png",
                "github",
                Set.of("SUPER_ADMIN")
        );
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );

        when(skillRatingService.getUserRating(eq(10L), eq("user-42")))
                .thenReturn(Optional.of((short) 4));

        mockMvc.perform(get("/api/v1/skills/10/rating")
                        .with(authentication(auth))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.score").value(4))
                .andExpect(jsonPath("$.data.rated").value(true))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void rate_skill_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(put("/api/v1/skills/10/rating")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\": 4}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void get_user_rating_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/skills/10/rating"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void inaccessibleSkillCannotBeReadOrMutated(String prefix) throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal("outsider", "Outsider", null, null, "local", Set.of("USER"));
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mockMvc.perform(get(prefix + "/skills/10/rating").with(authentication(auth)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("restricted-target"))));
        mockMvc.perform(put(prefix + "/skills/10/rating").with(authentication(auth)).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"score\":4}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("restricted-target"))));
        org.mockito.Mockito.verifyNoInteractions(skillRatingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/web"})
    void namespaceAdministratorUsesTrustedMembership(String prefix) throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal("ns-admin", "Admin", null, null, "local", Set.of("USER"));
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(namespaceMemberRepository.findByUserId("ns-admin")).thenReturn(List.of(
                new com.iflytek.skillhub.domain.namespace.NamespaceMember(1L, "ns-admin",
                        com.iflytek.skillhub.domain.namespace.NamespaceRole.ADMIN)));
        mockMvc.perform(put(prefix + "/skills/10/rating").with(authentication(auth)).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"score\":4}"))
                .andExpect(status().isOk());
    }

}
