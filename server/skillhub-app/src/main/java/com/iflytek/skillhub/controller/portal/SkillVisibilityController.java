package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillVisibilityAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillVisibilityController extends BaseApiController {
    private final SkillVisibilityAppService service;

    public SkillVisibilityController(SkillVisibilityAppService service, ApiResponseFactory responses) {
        super(responses); this.service = service;
    }

    @io.swagger.v3.oas.annotations.Operation(operationId = "getSkillVisibility")
    @GetMapping("/{namespace}/{slug}/visibility")
    public ApiResponse<SkillVisibilityAppService.Result> current(@PathVariable String namespace,
            @PathVariable String slug, @RequestAttribute("userId") String actor,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> roles) {
        return ok("response.success", service.current(namespace, slug, actor, roles));
    }

    @io.swagger.v3.oas.annotations.Operation(operationId = "changeSkillVisibility")
    @PostMapping("/{namespace}/{slug}/visibility")
    public ApiResponse<SkillVisibilityAppService.Result> change(@PathVariable String namespace,
            @PathVariable String slug, @Valid @RequestBody ChangeVisibilityRequest request,
            @RequestAttribute("userId") String actor,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> roles,
            HttpServletRequest http) {
        return ok("response.success.updated", service.change(namespace, slug, request.visibility(), actor,
                roles, AuditRequestContext.from(http)));
    }

    public record ChangeVisibilityRequest(@NotNull SkillVisibility visibility) {}
}
