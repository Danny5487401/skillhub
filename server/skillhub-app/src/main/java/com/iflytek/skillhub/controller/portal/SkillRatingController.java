package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.SkillRatingRequest;
import com.iflytek.skillhub.dto.SkillRatingStatusResponse;
import com.iflytek.skillhub.service.SkillSocialAppService;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

/**
 * Endpoints for reading and mutating the current user's rating on a skill.
 */
@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillRatingController extends BaseApiController {

    private final SkillSocialAppService socialAppService;

    public SkillRatingController(ApiResponseFactory responseFactory,
                                 SkillSocialAppService socialAppService) {
        super(responseFactory);
        this.socialAppService = socialAppService;
    }

    @PutMapping("/{skillId}/rating")
    public ApiResponse<Void> rateSkill(
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRatingRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> namespaceRoles) {
        socialAppService.rate(skillId, principal.userId(), request.score(), namespaceRoles, principal.platformRoles());
        return ok("response.success.updated", null);
    }

    @GetMapping("/{skillId}/rating")
    public ApiResponse<SkillRatingStatusResponse> getUserRating(
            @PathVariable Long skillId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> namespaceRoles) {
        Optional<Short> rating = socialAppService.getUserRating(skillId,
                principal != null ? principal.userId() : null, namespaceRoles,
                principal != null ? principal.platformRoles() : null);
        return ok(
                "response.success.read",
                new SkillRatingStatusResponse(
                        rating.orElse((short) 0),
                        rating.isPresent()
                )
        );
    }
}
