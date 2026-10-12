package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.portal.NamespaceInvitationController;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationService;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.NamespaceInvitationCreateRequest;
import com.iflytek.skillhub.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
}
