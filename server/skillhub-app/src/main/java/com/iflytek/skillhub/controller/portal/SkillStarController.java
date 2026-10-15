package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.domain.social.SkillStarService;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.service.SkillSocialAppService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints for starring, unstarring, and checking star state on a skill.
 */
@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillStarController extends BaseApiController {

    private final SkillStarService skillStarService;
    private final SkillSocialAppService socialAppService;

    public SkillStarController(ApiResponseFactory responseFactory,
                               SkillStarService skillStarService,
                               SkillSocialAppService socialAppService) {
        super(responseFactory);
        this.skillStarService = skillStarService;
        this.socialAppService = socialAppService;
    }

    @PutMapping("/{skillId}/star")
    public ApiResponse<Void> starSkill(
            @PathVariable Long skillId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> namespaceRoles) {
        socialAppService.star(skillId, principal.userId(), namespaceRoles, principal.platformRoles());
        return ok("response.success.updated", null);
    }

    @DeleteMapping("/{skillId}/star")
    public ApiResponse<Void> unstarSkill(
            @PathVariable Long skillId,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        skillStarService.unstar(skillId, principal.userId());
        return ok("response.success.updated", null);
    }

    @GetMapping("/{skillId}/star")
    public ApiResponse<Boolean> checkStarred(
            @PathVariable Long skillId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> namespaceRoles) {
        boolean starred = socialAppService.isStarred(skillId,
                principal != null ? principal.userId() : null, namespaceRoles,
                principal != null ? principal.platformRoles() : null);
        return ok("response.success.read", starred);
    }
}
