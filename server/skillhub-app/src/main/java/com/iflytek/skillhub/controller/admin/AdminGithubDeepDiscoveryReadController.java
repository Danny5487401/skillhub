package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.*;
import com.iflytek.skillhub.service.GithubDeepDiscoveryReadAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/github-sync/deep-discovery")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name="GitHub Sync Admin")
public class AdminGithubDeepDiscoveryReadController extends BaseApiController {
    private final GithubDeepDiscoveryReadAppService reads;
    public AdminGithubDeepDiscoveryReadController(ApiResponseFactory factory,GithubDeepDiscoveryReadAppService reads) {
        super(factory);this.reads=reads;
    }
    @GetMapping("/summary") @Operation(summary="查看官方探索近期成果与当前积压")
    public ResponseEntity<ApiResponse<Summary>> summary(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant until) { return response(reads.summary(from,until)); }
    @GetMapping("/progress") @Operation(summary="查看官方探索渠道进度与等待原因")
    public ResponseEntity<ApiResponse<Progress>> progress() { return response(reads.progress()); }
    @GetMapping("/repositories") @Operation(summary="分页查询官方探索命中的仓库")
    public ResponseEntity<ApiResponse<DiscoveryPage<RepositorySummary>>> repositories(
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required=false) String q,@RequestParam(required=false) RepositoryStatus status,
            @RequestParam(required=false) Channel channel,@RequestParam(required=false) Boolean paused) {
        return response(reads.repositories(page,size,asOf,q,status,channel,paused));
    }
    @GetMapping("/repositories/{repositoryId}") @Operation(summary="按需读取仓库检查摘要")
    public ResponseEntity<ApiResponse<RepositoryDetail>> repository(@PathVariable long repositoryId) {
        return response(reads.repository(repositoryId));
    }
    @GetMapping("/repositories/{repositoryId}/provenance") @Operation(summary="分页查询仓库命中出处")
    public ResponseEntity<ApiResponse<DiscoveryPage<Provenance>>> provenance(@PathVariable long repositoryId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return response(reads.provenance(repositoryId,page,size,asOf));
    }
    @GetMapping("/repositories/{repositoryId}/paths") @Operation(summary="分页查询仓库技能路径最新结果")
    public ResponseEntity<ApiResponse<DiscoveryPage<PathResult>>> paths(@PathVariable long repositoryId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return response(reads.paths(repositoryId,page,size,asOf));
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok("response.success.read",value));
    }
}
