package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.portal.NamespaceInvitationController;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationService;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.NamespaceInvitationCreateRequest;
import com.iflytek.skillhub.dto.NamespaceInvitationTokenRequest;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.exception.UnauthorizedException;
import com.iflytek.skillhub.exception.GlobalExceptionHandler;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NamespaceInvitationControllerTest {
    private final NamespaceInvitationService invitations = mock(NamespaceInvitationService.class);
    private final ApiResponseFactory responses = mock(ApiResponseFactory.class);
    private final NamespaceInvitationController controller = new NamespaceInvitationController(invitations, responses);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    void refusesApiTokenEvenWhenItCarriesAnAdminPlatformRole() {
        when(request.getSession(false)).thenReturn(mock(HttpSession.class));
        PlatformPrincipal token = new PlatformPrincipal("admin", "Admin", null, null, "api_token", Set.of("SUPER_ADMIN"));
        assertThatThrownBy(() -> controller.create("team", new NamespaceInvitationCreateRequest(true), token, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(invitations);
    }

    @Test
    void refusesApiTokenForInvitationStatusAndRevoke() {
        when(request.getSession(false)).thenReturn(mock(HttpSession.class));
        PlatformPrincipal token = new PlatformPrincipal("admin", "Admin", null, null, "api_token", Set.of("SUPER_ADMIN"));

        assertThatThrownBy(() -> controller.status("team", token, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(invitations);
        assertThatThrownBy(() -> controller.revoke("team", token, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(invitations);
    }

    @Test
    void refusesAuthenticationWithoutAnExistingBrowserSession() {
        PlatformPrincipal principal = new PlatformPrincipal("owner", "Owner", null, null, "local", Set.of());
        assertThatThrownBy(() -> controller.preview(new com.iflytek.skillhub.dto.NamespaceInvitationTokenRequest("secret"),
                principal, request, response)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(invitations);
    }

    @Test
    void sendsNoStoreHeadersBeforeReturningAnInvitation() {
        when(request.getSession(false)).thenReturn(mock(HttpSession.class));
        PlatformPrincipal principal = new PlatformPrincipal("owner", "Owner", null, null, "local", Set.of());
        controller.create("team", new NamespaceInvitationCreateRequest(false), principal, request, response);
        verify(response).setHeader("Cache-Control", "no-store, private");
        verify(response).setHeader("Pragma", "no-cache");
        verify(invitations).create("team", "owner", false);
    }

    @Test
    void issueRoutesUsePluralPathsForBothApiPrefixes() throws NoSuchMethodException {
        assertThat(NamespaceInvitationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1", "/api/web");
        assertThat(NamespaceInvitationController.class.getMethod("create", String.class,
                NamespaceInvitationCreateRequest.class, PlatformPrincipal.class, HttpServletRequest.class,
                HttpServletResponse.class).getAnnotation(PostMapping.class).value())
                .containsExactly("/namespaces/{slug}/invitations");
        assertThat(NamespaceInvitationController.class.getMethod("status", String.class, PlatformPrincipal.class,
                HttpServletRequest.class, HttpServletResponse.class).getAnnotation(GetMapping.class).value())
                .containsExactly("/namespaces/{slug}/invitations");
        assertThat(NamespaceInvitationController.class.getMethod("revoke", String.class, PlatformPrincipal.class,
                HttpServletRequest.class, HttpServletResponse.class).getAnnotation(DeleteMapping.class).value())
                .containsExactly("/namespaces/{slug}/invitations");
        assertThat(NamespaceInvitationController.class.getMethod("preview", NamespaceInvitationTokenRequest.class,
                PlatformPrincipal.class, HttpServletRequest.class, HttpServletResponse.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/namespace-invitations/preview");
        assertThat(NamespaceInvitationController.class.getMethod("accept", NamespaceInvitationTokenRequest.class,
                PlatformPrincipal.class, HttpServletRequest.class, HttpServletResponse.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/namespace-invitations/accept");
    }

    @Test
    void historyRequiresSessionAndNeverReturnsTokensOrHashes() throws Exception {
        PlatformPrincipal owner = new PlatformPrincipal("owner", "Owner", null, null, "local", Set.of());
        assertThatThrownBy(() -> controller.history("team", 0, 10, owner, request, response))
                .isInstanceOf(UnauthorizedException.class);
        when(request.getSession(false)).thenReturn(mock(HttpSession.class));
        PlatformPrincipal token = new PlatformPrincipal("owner", "Owner", null, null, "api_token", Set.of("SUPER_ADMIN"));
        assertThatThrownBy(() -> controller.history("team", 0, 10, token, request, response))
                .isInstanceOf(UnauthorizedException.class);
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        var row = new com.iflytek.skillhub.domain.namespace.NamespaceInvitationHistory(7L, "owner", "Original name", now, now.plusSeconds(86400));
        when(invitations.history("team", "owner", 0, 10)).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(row), org.springframework.data.domain.PageRequest.of(0, 10), 1));
        when(invitations.currentTime()).thenReturn(now);
        when(responses.ok(anyString(), any())).thenAnswer(call -> new ApiResponse<>(0, "ok", call.getArgument(1), now, null));
        var page = controller.history("team", 0, 10, owner, request, response).data();
        assertThat(page.items().getFirst().status()).isEqualTo("ACTIVE");
        assertThat(page.total()).isEqualTo(1);
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(page);
        assertThat(json).contains("invitationId", "Original name").doesNotContain("token", "hash");
        verify(response, times(3)).setHeader("Cache-Control", "no-store, private");
    }

    @Test
    void firstCreateNeedsNoBodyAndSuccessDtosMatchIssueContract() {
        when(request.getSession(false)).thenReturn(mock(HttpSession.class));
        PlatformPrincipal owner = new PlatformPrincipal("owner", "Owner", null, null, "local", Set.of());
        Instant expiry = Instant.parse("2026-10-13T00:00:00Z");
        NamespaceInvitationService.Created created = new NamespaceInvitationService.Created("raw-token", expiry, false);
        when(invitations.create("team", "owner", false)).thenReturn(created);
        when(responses.ok(anyString(), any())).thenAnswer(call -> new ApiResponse<>(0, "ok", call.getArgument(1), expiry, null));

        assertThat(controller.create("team", null, owner, request, response).data()).isEqualTo(created);
        verify(invitations).create("team", "owner", false);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertThat(mapper.valueToTree(created).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("token", "expiresAt", "replaced");
        for (NamespaceInvitationService.PreviewOutcome outcome : NamespaceInvitationService.PreviewOutcome.values()) {
            NamespaceInvitationService.Preview preview = new NamespaceInvitationService.Preview(
                    "team", "Team", null, outcome, expiry);
            assertThat(mapper.valueToTree(preview).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("namespaceSlug", "namespaceDisplayName", "namespaceAvatarUrl", "outcome", "expiresAt");
            assertThat(mapper.valueToTree(preview).get("outcome").asText()).isEqualTo(outcome.name());
        }
        for (NamespaceInvitationService.AcceptOutcome outcome : NamespaceInvitationService.AcceptOutcome.values()) {
            NamespaceInvitationService.Accepted accepted = new NamespaceInvitationService.Accepted("team", outcome);
            assertThat(mapper.valueToTree(accepted).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("namespaceSlug", "outcome");
            assertThat(mapper.valueToTree(accepted).get("outcome").asText()).isEqualTo(outcome.name());
        }
    }

    @Test
    void invalidInvitationUsesIssueErrorKeyAndLocalized404Envelope() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        ApiResponseFactory factory = new ApiResponseFactory(messages, Clock.fixed(
                Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC), new RequestIdAccessor());
        GlobalExceptionHandler handler = new GlobalExceptionHandler(factory,
                mock(SensitiveLogSanitizer.class), mock(SkillHubMetrics.class), new RequestIdAccessor());
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            var result = handler.handleLocalizedDomainException(
                    new DomainNotFoundException("error.namespace.invitation.invalid"), request);
            assertThat(result.getStatusCode().value()).isEqualTo(404);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo(404);
            assertThat(result.getBody().msg()).isEqualTo("邀请链接无效或已失效");
            assertThat(result.getBody().data()).isNull();
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
