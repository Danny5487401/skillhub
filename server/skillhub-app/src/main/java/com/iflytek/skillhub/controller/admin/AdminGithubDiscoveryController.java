package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubCuratedOwnerRequest;
import com.iflytek.skillhub.dto.GithubCuratedRepositoryRequest;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryEnabledRequest;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryResponse;
import com.iflytek.skillhub.dto.GithubSearchRuleRequest;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubDiscoveryAdminAppService;
import com.iflytek.skillhub.service.GithubSearchPreviewAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/admin/github-sync/discovery-entries")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "GitHub Sync Admin")
public class AdminGithubDiscoveryController extends BaseApiController {
    private final GithubDiscoveryAdminAppService discovery;
    private final GithubSearchPreviewAppService previews;

    public AdminGithubDiscoveryController(ApiResponseFactory responseFactory,
                                          GithubDiscoveryAdminAppService discovery,
                                          GithubSearchPreviewAppService previews) {
        super(responseFactory);
        this.discovery = discovery;
        this.previews = previews;
    }

    @GetMapping
    @Operation(summary = "查看 GitHub 发现规则与精选入口")
    public ApiResponse<List<GithubDiscoveryEntryResponse>> list() {
        return ok("response.success.read", discovery.list());
    }

    @PostMapping("/search-rules")
    @Operation(summary = "创建初始停用的仓库搜索规则")
    public ApiResponse<GithubDiscoveryEntryResponse> createSearchRule(
            @Valid @RequestBody GithubSearchRuleRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.created", discovery.createSearchRule(
                body, principal.userId(), AuditRequestContext.from(request)));
    }

    @PostMapping("/search-rules/preview")
    @Operation(summary = "预览搜索规则的仓库命中情况，不登记来源")
    public ApiResponse<GithubSearchPreviewAppService.Preview> previewSearchRule(
            @Valid @RequestBody GithubSearchRuleRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ok("response.success.created", previews.queue(body, principal.userId()));
    }

    @GetMapping("/search-rules/previews/{id}")
    @Operation(summary = "查看搜索规则预览结果")
    public ApiResponse<GithubSearchPreviewAppService.Preview> searchRulePreview(@PathVariable UUID id) {
        return ok("response.success.read", previews.get(id));
    }

    @PutMapping("/search-rules/{id}")
    @Operation(summary = "编辑仓库搜索规则并生成新版本")
    public ApiResponse<GithubDiscoveryEntryResponse> updateSearchRule(
            @PathVariable UUID id,
            @Valid @RequestBody GithubSearchRuleRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.updated", discovery.updateSearchRule(
                id, body, principal.userId(), AuditRequestContext.from(request)));
    }

    @PostMapping("/curated-owners")
    @Operation(summary = "登记精选 GitHub 用户或组织")
    public ApiResponse<GithubDiscoveryEntryResponse> createCuratedOwner(
            @Valid @RequestBody GithubCuratedOwnerRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.created", discovery.createCuratedOwner(
                body, principal.userId(), AuditRequestContext.from(request)));
    }

    @PostMapping("/curated-repositories")
    @Operation(summary = "登记精选 GitHub 公开仓库")
    public ApiResponse<GithubDiscoveryEntryResponse> createCuratedRepository(
            @Valid @RequestBody GithubCuratedRepositoryRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.created", discovery.createCuratedRepository(
                body, principal.userId(), AuditRequestContext.from(request)));
    }

    @PutMapping("/{id}/enabled")
    @Operation(summary = "启用或停用 GitHub 发现入口")
    public ApiResponse<GithubDiscoveryEntryResponse> setEnabled(
            @PathVariable UUID id,
            @Valid @RequestBody GithubDiscoveryEntryEnabledRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.updated", discovery.setEnabled(
                id, body.enabled(), principal.userId(), AuditRequestContext.from(request)));
    }
}
