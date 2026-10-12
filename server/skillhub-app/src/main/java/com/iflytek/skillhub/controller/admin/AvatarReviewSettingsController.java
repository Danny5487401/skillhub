package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.AvatarReviewSettingsResponse;
import com.iflytek.skillhub.dto.AvatarReviewSettingsUpdateRequest;
import com.iflytek.skillhub.service.AvatarReviewSettingsService;
import com.iflytek.skillhub.service.AuditRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system-config/avatar-review")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AvatarReviewSettingsController extends BaseApiController {
    private final AvatarReviewSettingsService service;

    public AvatarReviewSettingsController(AvatarReviewSettingsService service, ApiResponseFactory responses) {
        super(responses);
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AvatarReviewSettingsResponse> current() {
        return ok("response.success.read", service.current());
    }

    @PutMapping
    public ApiResponse<AvatarReviewSettingsResponse> update(@Valid @RequestBody AvatarReviewSettingsUpdateRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest httpRequest) {
        return ok("response.success.updated", service.update(request, principal.userId(),
                AuditRequestContext.from(httpRequest)));
    }
}
