package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.MessageResponse;
import com.iflytek.skillhub.dto.NamespaceInvitationTokenRequest;
import com.iflytek.skillhub.dto.NamespaceInvitationCreateRequest;
import com.iflytek.skillhub.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1", "/api/web"})
public class NamespaceInvitationController extends BaseApiController {
    private final NamespaceInvitationService invitations;

    public NamespaceInvitationController(NamespaceInvitationService invitations, ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.invitations = invitations;
    }

    @GetMapping("/namespaces/{slug}/invitation")
    public ApiResponse<InvitationStatus> status(@PathVariable String slug, @AuthenticationPrincipal PlatformPrincipal principal,
                                                 HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requireSession(principal, request);
        return ok("response.success.read", new InvitationStatus(invitations.hasActiveInvitation(slug, principal.userId())));
    }

    @PostMapping("/namespaces/{slug}/invitation")
    public ApiResponse<NamespaceInvitationService.Created> create(@PathVariable String slug,
            @RequestBody(required = false) NamespaceInvitationCreateRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requireSession(principal, request);
        return ok("response.success.created", invitations.create(slug, principal.userId(), body != null && body.replace()));
    }

    @DeleteMapping("/namespaces/{slug}/invitation")
    public ApiResponse<MessageResponse> revoke(@PathVariable String slug,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requireSession(principal, request);
        invitations.revoke(slug, principal.userId());
        return ok("response.success.deleted", new MessageResponse("Invitation revoked"));
    }

    @PostMapping("/invitations/preview")
    public ApiResponse<NamespaceInvitationService.Preview> preview(@Valid @RequestBody NamespaceInvitationTokenRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requireSession(principal, request);
        return ok("response.success.read", invitations.preview(body.token(), principal.userId()));
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<NamespaceInvitationService.Preview> accept(@Valid @RequestBody NamespaceInvitationTokenRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requireSession(principal, request);
        return ok("response.success.updated", invitations.accept(body.token(), principal.userId()));
    }

    private void requireSession(PlatformPrincipal principal, HttpServletRequest request) {
        if (principal == null || "api_token".equals(principal.oauthProvider()) || request.getSession(false) == null) {
            throw new UnauthorizedException("error.auth.required");
        }
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Pragma", "no-cache");
    }

    public record InvitationStatus(boolean active) {}
}
