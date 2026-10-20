package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryConfigurationRequest;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryConfigurationResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubDeepDiscoveryConfigurationAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/github-sync/deep-discovery/configuration")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "GitHub Sync Admin")
public class AdminGithubDeepDiscoveryController extends BaseApiController {
    private final GithubDeepDiscoveryConfigurationAppService configurations;

    public AdminGithubDeepDiscoveryController(ApiResponseFactory responseFactory,
            GithubDeepDiscoveryConfigurationAppService configurations) {
        super(responseFactory);
        this.configurations = configurations;
    }

    @GetMapping
    @Operation(summary = "读取官方 GitHub 深度探索配置", description = "配置启用与采集服务在线及执行状态独立")
    public ResponseEntity<ApiResponse<GithubDeepDiscoveryConfigurationResponse>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.read", configurations.get()));
    }

    @PutMapping
    @Operation(summary = "按配置版本更新官方 GitHub 深度探索设置")
    public ResponseEntity<ApiResponse<GithubDeepDiscoveryConfigurationResponse>> update(
            @Valid @RequestBody GithubDeepDiscoveryConfigurationRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ok("response.success.updated", configurations.update(
                        body, principal.userId(), AuditRequestContext.from(request))));
    }
}
