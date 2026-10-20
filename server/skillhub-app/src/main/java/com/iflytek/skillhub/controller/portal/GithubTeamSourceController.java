package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryEnabledRequest;
import com.iflytek.skillhub.dto.GithubDiscoveryEntryResponse;
import com.iflytek.skillhub.dto.GithubTeamRepositoryRequest;
import com.iflytek.skillhub.dto.GithubTeamSourceVisibilityRequest;
import com.iflytek.skillhub.dto.GithubSyncRunSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSourceSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSkillPathResponse;
import com.iflytek.skillhub.dto.GithubSyncManualBindingRequest;
import com.iflytek.skillhub.dto.GithubSyncManualBindingResponse;
import com.iflytek.skillhub.dto.GithubSourceIgnoreRequest;
import com.iflytek.skillhub.dto.GithubSourceIgnoreResponse;
import com.iflytek.skillhub.dto.GithubSourceRescanResponse;
import com.iflytek.skillhub.dto.GithubSyncRetrySkillRequest;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubTeamSourceAppService;
import com.iflytek.skillhub.service.GithubSourceIgnoreAppService;
import com.iflytek.skillhub.service.GithubSyncManualBindingAppService;
import com.iflytek.skillhub.service.GithubSourceRescanAppService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/namespaces/{namespaceSlug}/github-sources",
        "/api/web/namespaces/{namespaceSlug}/github-sources"})
public class GithubTeamSourceController extends BaseApiController {
    private final GithubTeamSourceAppService sources;
    private final GithubSourceIgnoreAppService ignores;
    private final GithubSyncManualBindingAppService manualBindings;
    private final GithubSourceRescanAppService rescans;

    public GithubTeamSourceController(ApiResponseFactory responseFactory, GithubTeamSourceAppService sources,
                                      GithubSourceIgnoreAppService ignores,
                                      GithubSyncManualBindingAppService manualBindings,
                                      GithubSourceRescanAppService rescans) {
        super(responseFactory);
        this.sources = sources;
        this.ignores = ignores;
        this.manualBindings = manualBindings;
        this.rescans = rescans;
    }

    @PostMapping("/resolved/{sourceId}/rescan")
    @Operation(summary = "按现有任务队列安排团队 GitHub 来源重新检查")
    public ApiResponse<GithubSourceRescanResponse> rescan(
            @PathVariable String namespaceSlug, @PathVariable long sourceId,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", rescans.team(namespaceSlug, sourceId, userId,
                AuditRequestContext.from(request)));
    }

    @PostMapping("/resolved/{sourceId}/retry-rejected-skill")
    @Operation(summary = "重新采集并送审一个已拒绝的团队 GitHub Skill")
    public ApiResponse<GithubSourceRescanResponse> retryRejectedSkill(
            @PathVariable String namespaceSlug, @PathVariable long sourceId,
            @Valid @RequestBody GithubSyncRetrySkillRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", rescans.retryTeamSkill(namespaceSlug, sourceId,
                body.skillPath(), userId, AuditRequestContext.from(request)));
    }

    @PostMapping("/conflicts/{ingestionId}/bind-skill")
    @Operation(summary = "确认团队 GitHub 来源与既有 Skill ID 的绑定")
    public ApiResponse<GithubSyncManualBindingResponse> bindExistingSkill(
            @PathVariable String namespaceSlug, @PathVariable UUID ingestionId,
            @Valid @RequestBody GithubSyncManualBindingRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", manualBindings.bindTeam(
                namespaceSlug, ingestionId, body.skillId(), body.reason(), userId,
                AuditRequestContext.from(request)));
    }

    @GetMapping
    @Operation(summary = "查看团队 GitHub 公开仓库来源配置")
    public ApiResponse<List<GithubDiscoveryEntryResponse>> list(
            @PathVariable String namespaceSlug, @RequestAttribute("userId") String userId) {
        return ok("response.success.read", sources.list(namespaceSlug, userId));
    }

    @GetMapping("/resolved")
    @Operation(summary = "分页查看团队已解析 GitHub 来源与摄入进度")
    public ApiResponse<PageResponse<GithubSyncSourceSummaryResponse>> resolved(
            @PathVariable String namespaceSlug, @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", sources.resolvedSources(namespaceSlug, userId, page, size));
    }

    @GetMapping("/runs")
    @Operation(summary = "分页查看团队 GitHub 来源的发现与采集运行")
    public ApiResponse<PageResponse<GithubSyncRunSummaryResponse>> runs(
            @PathVariable String namespaceSlug, @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", sources.runs(namespaceSlug, userId, page, size));
    }

    @GetMapping("/resolved/{sourceId}/skills")
    @Operation(summary = "分页查看团队 GitHub 来源的逐 Skill 路径状态")
    public ApiResponse<PageResponse<GithubSyncSkillPathResponse>> skillPaths(
            @PathVariable String namespaceSlug, @PathVariable long sourceId,
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", sources.skillPaths(
                namespaceSlug, userId, sourceId, page, size));
    }

    @GetMapping("/resolved/{sourceId}/ignore-patterns")
    @Operation(summary = "查看团队 GitHub 来源的候选路径忽略规则")
    public ApiResponse<GithubSourceIgnoreResponse> ignorePatterns(
            @PathVariable String namespaceSlug, @PathVariable long sourceId,
            @RequestAttribute("userId") String userId) {
        return ok("response.success.read", ignores.team(namespaceSlug, sourceId, userId));
    }

    @PutMapping("/resolved/{sourceId}/ignore-patterns")
    @Operation(summary = "设置团队 GitHub 来源的候选路径忽略规则")
    public ApiResponse<GithubSourceIgnoreResponse> updateIgnorePatterns(
            @PathVariable String namespaceSlug, @PathVariable long sourceId,
            @Valid @RequestBody GithubSourceIgnoreRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", ignores.updateTeam(namespaceSlug, sourceId,
                body.ignorePatterns(), userId, AuditRequestContext.from(request)));
    }

    @PostMapping
    @Operation(summary = "为团队登记 GitHub 公开仓库")
    public ApiResponse<GithubDiscoveryEntryResponse> create(
            @PathVariable String namespaceSlug, @Valid @RequestBody GithubTeamRepositoryRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.created", sources.create(namespaceSlug, body, userId,
                AuditRequestContext.from(request)));
    }

    @PutMapping("/{entryId}/enabled")
    @Operation(summary = "暂停或恢复团队 GitHub 来源同步")
    public ApiResponse<GithubDiscoveryEntryResponse> setEnabled(
            @PathVariable String namespaceSlug, @PathVariable UUID entryId,
            @Valid @RequestBody GithubDiscoveryEntryEnabledRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", sources.setEnabled(namespaceSlug, entryId, body.enabled(),
                userId, AuditRequestContext.from(request)));
    }

    @PutMapping("/{entryId}/visibility")
    @Operation(summary = "设置团队来源后续导入内容的可见范围")
    public ApiResponse<GithubDiscoveryEntryResponse> setVisibility(
            @PathVariable String namespaceSlug, @PathVariable UUID entryId,
            @Valid @RequestBody GithubTeamSourceVisibilityRequest body,
            @RequestAttribute("userId") String userId, HttpServletRequest request) {
        return ok("response.success.updated", sources.setVisibility(namespaceSlug, entryId, body.visibility(),
                userId, AuditRequestContext.from(request)));
    }
}
