package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubSyncAdmissionPolicyResponse;
import com.iflytek.skillhub.dto.GithubSyncAdmissionPolicyUpdateRequest;
import com.iflytek.skillhub.dto.GithubSyncRunSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncRunDetailResponse;
import com.iflytek.skillhub.dto.GithubSyncRunTaskResponse;
import com.iflytek.skillhub.dto.GithubSyncRunPathResponse;
import com.iflytek.skillhub.dto.GithubSyncWorkerStatusResponse;
import com.iflytek.skillhub.dto.GithubSyncSourceSummaryResponse;
import com.iflytek.skillhub.dto.GithubSyncSkillPathResponse;
import com.iflytek.skillhub.dto.GithubSyncManualBindingRequest;
import com.iflytek.skillhub.dto.GithubSyncManualBindingResponse;
import com.iflytek.skillhub.dto.GithubSourceIgnoreRequest;
import com.iflytek.skillhub.dto.GithubSourceIgnoreResponse;
import com.iflytek.skillhub.dto.GithubSourceRescanResponse;
import com.iflytek.skillhub.dto.GithubSyncRetrySkillRequest;
import com.iflytek.skillhub.dto.GithubSyncSchedulePolicyRequest;
import com.iflytek.skillhub.dto.GithubSyncSchedulePolicyResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.RunKind;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.RunStatus;
import com.iflytek.skillhub.githubsync.GithubSyncAdmissionPolicy;
import com.iflytek.skillhub.repository.GithubSyncManagementQueryRepository;
import com.iflytek.skillhub.repository.GithubSyncWorkerPresenceRepository;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubSyncAdmissionPolicyAppService;
import com.iflytek.skillhub.service.GithubSourceIgnoreAppService;
import com.iflytek.skillhub.service.GithubSyncManualBindingAppService;
import com.iflytek.skillhub.service.GithubSourceRescanAppService;
import com.iflytek.skillhub.service.GithubSyncSchedulePolicyAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/github-sync")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "GitHub Sync Admin")
public class AdminGithubSyncController extends BaseApiController {
    private final GithubSyncAdmissionPolicyAppService admissionPolicies;
    private final GithubSyncManagementQueryRepository management;
    private final GithubSyncWorkerPresenceRepository workerPresence;
    private final GithubSourceIgnoreAppService ignores;
    private final GithubSyncManualBindingAppService manualBindings;
    private final GithubSourceRescanAppService rescans;
    private final GithubSyncSchedulePolicyAppService schedulePolicies;

    public AdminGithubSyncController(ApiResponseFactory responseFactory,
                                     GithubSyncAdmissionPolicyAppService admissionPolicies,
                                     GithubSyncManagementQueryRepository management,
                                     GithubSyncWorkerPresenceRepository workerPresence,
                                     GithubSourceIgnoreAppService ignores,
                                     GithubSyncManualBindingAppService manualBindings,
                                     GithubSourceRescanAppService rescans,
                                     GithubSyncSchedulePolicyAppService schedulePolicies) {
        super(responseFactory);
        this.admissionPolicies = admissionPolicies;
        this.management = management;
        this.workerPresence = workerPresence;
        this.ignores = ignores;
        this.manualBindings = manualBindings;
        this.rescans = rescans;
        this.schedulePolicies = schedulePolicies;
    }

    @GetMapping("/schedule-policy")
    @Operation(summary = "读取 GitHub 同步三类任务目标周期")
    public ApiResponse<GithubSyncSchedulePolicyResponse> schedulePolicy() {
        return ok("response.success.read", schedulePolicies.get());
    }

    @GetMapping("/worker-status")
    @Operation(summary = "查看 GitHub 采集服务最后在线时间")
    public ApiResponse<GithubSyncWorkerStatusResponse> workerStatus() {
        return ok("response.success.read", workerPresence.status());
    }

    @PutMapping("/schedule-policy")
    @Operation(summary = "按类别调整 GitHub 同步任务目标周期")
    public ApiResponse<GithubSyncSchedulePolicyResponse> updateSchedulePolicy(
            @Valid @RequestBody GithubSyncSchedulePolicyRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ok("response.success.updated", schedulePolicies.update(body, principal.userId(),
                AuditRequestContext.from(request)));
    }

    @PostMapping("/sources/{sourceId}/rescan")
    @Operation(summary = "按现有任务队列安排平台 GitHub 来源重新检查")
    public ApiResponse<GithubSourceRescanResponse> rescan(@PathVariable long sourceId,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ok("response.success.updated", rescans.platform(sourceId, principal.userId(),
                AuditRequestContext.from(request)));
    }

    @PostMapping("/sources/{sourceId}/retry-rejected-skill")
    @Operation(summary = "重新采集并送审一个已拒绝的 GitHub Skill")
    public ApiResponse<GithubSourceRescanResponse> retryRejectedSkill(@PathVariable long sourceId,
            @Valid @RequestBody GithubSyncRetrySkillRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ok("response.success.updated", rescans.retryPlatformSkill(sourceId, body.skillPath(),
                principal.userId(), AuditRequestContext.from(request)));
    }

    @PostMapping("/conflicts/{ingestionId}/bind-skill")
    @Operation(summary = "确认平台 GitHub 来源与既有 Skill ID 的绑定")
    public ApiResponse<GithubSyncManualBindingResponse> bindExistingSkill(
            @PathVariable UUID ingestionId, @Valid @RequestBody GithubSyncManualBindingRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ok("response.success.updated", manualBindings.bindPlatform(
                ingestionId, body.skillId(), body.reason(), principal.userId(),
                AuditRequestContext.from(request)));
    }

    @GetMapping("/sources")
    @Operation(summary = "分页查看平台 GitHub 来源及摄入进度")
    public ApiResponse<PageResponse<GithubSyncSourceSummaryResponse>> sources(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", management.sources(management.platformNamespaceId(), page, size));
    }

    @GetMapping("/runs")
    @Operation(summary = "分页查看平台 GitHub 发现与仓库采集运行")
    public ApiResponse<PageResponse<GithubSyncRunSummaryResponse>> runs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RunKind kind,
            @RequestParam(required = false) RunStatus status) {
        return ok("response.success.read", management.runs(management.platformNamespaceId(), page, size, kind, status));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "查看平台 GitHub 同步单次运行详情")
    public ApiResponse<GithubSyncRunDetailResponse> runDetail(@PathVariable UUID runId) {
        return ok("response.success.read", management.runDetail(management.platformNamespaceId(), runId));
    }

    @GetMapping("/runs/{runId}/tasks")
    @Operation(summary = "分页查看平台 GitHub 同步单次运行的任务")
    public ApiResponse<PageResponse<GithubSyncRunTaskResponse>> runTasks(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", management.runTasks(
                management.platformNamespaceId(), runId, page, size));
    }

    @GetMapping("/runs/{runId}/paths")
    @Operation(summary = "分页查看平台 GitHub 同步单次运行的逐 Skill 路径结果")
    public ApiResponse<PageResponse<GithubSyncRunPathResponse>> runPaths(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", management.runPaths(
                management.platformNamespaceId(), runId, page, size));
    }

    @GetMapping("/sources/{sourceId}/skills")
    @Operation(summary = "分页查看平台 GitHub 来源的逐 Skill 路径状态")
    public ApiResponse<PageResponse<GithubSyncSkillPathResponse>> skillPaths(
            @PathVariable long sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", management.skillPaths(
                management.platformNamespaceId(), sourceId, page, size));
    }

    @GetMapping("/sources/{sourceId}/ignore-patterns")
    @Operation(summary = "查看平台 GitHub 来源的候选路径忽略规则")
    public ApiResponse<GithubSourceIgnoreResponse> ignorePatterns(@PathVariable long sourceId) {
        return ok("response.success.read", ignores.platform(sourceId));
    }

    @PutMapping("/sources/{sourceId}/ignore-patterns")
    @Operation(summary = "设置平台 GitHub 来源的候选路径忽略规则")
    public ApiResponse<GithubSourceIgnoreResponse> updateIgnorePatterns(
            @PathVariable long sourceId, @Valid @RequestBody GithubSourceIgnoreRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ok("response.success.updated", ignores.updatePlatform(sourceId, body.ignorePatterns(),
                principal.userId(), AuditRequestContext.from(request)));
    }

    @GetMapping("/admission-policy")
    @Operation(summary = "读取 GitHub 同步准入水位与当前积压")
    public ApiResponse<GithubSyncAdmissionPolicyResponse> getAdmissionPolicy() {
        return ok("response.success.read", admissionPolicyResponse(admissionPolicies.get()));
    }

    @PutMapping("/admission-policy")
    @Operation(summary = "更新 GitHub 同步准入水位与候选预算")
    public ApiResponse<GithubSyncAdmissionPolicyResponse> updateAdmissionPolicy(
            @Valid @RequestBody GithubSyncAdmissionPolicyUpdateRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        return ok("response.success.updated", admissionPolicyResponse(admissionPolicies.update(
                body.globalHigh(), body.globalLow(), body.namespaceHigh(), body.namespaceLow(),
                body.candidatePendingLimit(), body.candidatePerLeaseLimit(),
                principal.userId(), AuditRequestContext.from(request))));
    }

    private GithubSyncAdmissionPolicyResponse admissionPolicyResponse(GithubSyncAdmissionPolicy policy) {
        var snapshot = management.admissionSnapshot();
        return GithubSyncAdmissionPolicyResponse.from(policy, snapshot.globalUnresolvedCount(),
                snapshot.platformUnresolvedCount(), snapshot.platformPendingCandidateCount(),
                snapshot.platformPaused());
    }
}
