package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.githubsync.GithubPlatformControlRepository;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GithubPlatformControlAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GitHub Sync Admin")
@RestController
@RequestMapping("/api/v1/admin/github-sync/platform-control")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminGithubPlatformControlController extends BaseApiController {
    private final GithubPlatformControlAppService controls;
    public AdminGithubPlatformControlController(ApiResponseFactory factory, GithubPlatformControlAppService controls) {
        super(factory);
        this.controls = controls;
    }

    @Operation(summary = "读取平台技能采集控制状态")
    @GetMapping
    public ResponseEntity<ApiResponse<PlatformControlState>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok("response.success.read", response(controls.get())));
    }

    @Operation(summary = "暂停或恢复平台技能采集")
    @PutMapping
    public ResponseEntity<ApiResponse<PlatformControlState>> update(@Valid @RequestBody UpdateRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok("response.success.updated",
                response(controls.update(body.expectedVersion(), body.paused(), principal.userId(), AuditRequestContext.from(request)))));
    }

    @Schema(name = "GithubPlatformControlUpdateRequest")
    public record UpdateRequest(@NotNull @Min(1) Long expectedVersion, @NotNull Boolean paused) {}

    @Schema(name = "GithubPlatformControlState")
    public record PlatformControlState(long namespaceId, long version, boolean desiredPaused, String state,
            long pendingStopCount, long expiredUnconfirmedCount, Instant updatedAt) {}

    private PlatformControlState response(GithubPlatformControlRepository.State state) {
        return new PlatformControlState(state.namespaceId(), state.version(), state.desiredPaused(), state.state(),
                state.pendingStopCount(), state.expiredUnconfirmedCount(), state.updatedAt());
    }
}
