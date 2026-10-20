package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubCredentialEnabledRequest;
import com.iflytek.skillhub.dto.GithubCredentialResponse;
import com.iflytek.skillhub.dto.GithubCredentialUpsertRequest;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubCredentialAdminAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/github-sync/credentials")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "GitHub Sync Admin")
public class AdminGithubCredentialController extends BaseApiController {
    private final GithubCredentialAdminAppService credentials;

    public AdminGithubCredentialController(ApiResponseFactory responseFactory,
                                           GithubCredentialAdminAppService credentials) {
        super(responseFactory);
        this.credentials = credentials;
    }

    @GetMapping
    @Operation(summary = "查看系统级公开 GitHub 凭证的脱敏状态")
    public ResponseEntity<ApiResponse<List<GithubCredentialResponse>>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.read", credentials.list()));
    }

    @PostMapping
    @Operation(summary = "加密保存系统级公开 GitHub 凭证")
    public ResponseEntity<ApiResponse<GithubCredentialResponse>> create(
            @Valid @RequestBody GithubCredentialUpsertRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.created", credentials.create(
                        body, principal.userId(), AuditRequestContext.from(request))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "替换系统级公开 GitHub 凭证并恢复健康状态")
    public ResponseEntity<ApiResponse<GithubCredentialResponse>> replace(
            @PathVariable UUID id, @Valid @RequestBody GithubCredentialUpsertRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.updated", credentials.replace(
                        id, body, principal.userId(), AuditRequestContext.from(request))));
    }

    @PutMapping("/{id}/enabled")
    @Operation(summary = "启用或停用系统级公开 GitHub 凭证")
    public ResponseEntity<ApiResponse<GithubCredentialResponse>> setEnabled(
            @PathVariable UUID id, @Valid @RequestBody GithubCredentialEnabledRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.updated", credentials.setEnabled(
                        id, body.enabled(), principal.userId(), AuditRequestContext.from(request))));
    }
}
