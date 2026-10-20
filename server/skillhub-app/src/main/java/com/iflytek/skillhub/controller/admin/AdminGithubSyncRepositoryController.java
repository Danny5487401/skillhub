package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.PathResult;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Detail;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Origin;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.OriginType;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Page;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.SourceStatus;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.State;
import com.iflytek.skillhub.dto.GithubSyncRepositoryResponse.Summary;
import com.iflytek.skillhub.repository.GithubSyncRepositoryQueryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/github-sync/repositories")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "GitHub Sync Admin")
public class AdminGithubSyncRepositoryController extends BaseApiController {
    private final GithubSyncRepositoryQueryRepository queries;

    public AdminGithubSyncRepositoryController(ApiResponseFactory factory, GithubSyncRepositoryQueryRepository queries) {
        super(factory);
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "分页查看平台全部发现方式的仓库")
    public ResponseEntity<ApiResponse<Page<Summary>>> repositories(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String q, @RequestParam(required = false) OriginType originType,
            @RequestParam(required = false) State state, @RequestParam(required = false) SourceStatus sourceStatus) {
        return response(queries.repositories(page, size, asOf, q, originType, state, sourceStatus));
    }

    @GetMapping("/{repositoryId}")
    @Operation(summary = "查看统一仓库登记和最近检查摘要")
    public ResponseEntity<ApiResponse<Detail>> repository(@PathVariable long repositoryId) {
        return response(queries.detail(repositoryId));
    }

    @GetMapping("/{repositoryId}/origins")
    @Operation(summary = "分页查看仓库全部持久出处")
    public ResponseEntity<ApiResponse<Page<Origin>>> origins(@PathVariable long repositoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return response(queries.origins(repositoryId, page, size, asOf));
    }

    @GetMapping("/{repositoryId}/paths")
    @Operation(summary = "分页查看统一仓库技能路径及审核链接")
    public ResponseEntity<ApiResponse<Page<PathResult>>> paths(@PathVariable long repositoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return response(queries.paths(repositoryId, page, size, asOf));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok("response.success.read", value));
    }
}
