package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GithubSyncReviewSourceResponse;
import com.iflytek.skillhub.dto.GithubSyncReviewQueueItemResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.service.GithubSyncReviewSourceAppService;
import com.iflytek.skillhub.service.GithubSyncReviewQueueAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/reviews/sync", "/api/web/reviews/sync"})
public class GithubSyncReviewSourceController extends BaseApiController {
    private final GithubSyncReviewSourceAppService sources;
    private final GithubSyncReviewQueueAppService queue;

    public GithubSyncReviewSourceController(ApiResponseFactory responseFactory,
                                            GithubSyncReviewSourceAppService sources,
                                            GithubSyncReviewQueueAppService queue) {
        super(responseFactory);
        this.sources = sources;
        this.queue = queue;
    }

    @GetMapping
    @Operation(summary = "按审核权限查询 GitHub 同步审核队列")
    public ApiResponse<PageResponse<GithubSyncReviewQueueItemResponse>> list(
            @RequestParam String status,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestAttribute("userId") String userId,
            @RequestAttribute(value = "userNsRoles", required = false)
            Map<Long, NamespaceRole> userNsRoles) {
        return ok("response.success.read", queue.list(status, namespaceId, page, size,
                sortDirection, userId, userNsRoles));
    }

    @GetMapping("/{id}/source")
    @Operation(summary = "查看同步审核的 GitHub 来源和许可证证据")
    public ApiResponse<GithubSyncReviewSourceResponse> getSource(
            @PathVariable Long id,
            @RequestAttribute("userId") String userId,
            @RequestAttribute(value = "userNsRoles", required = false)
            Map<Long, NamespaceRole> userNsRoles) {
        return ok("response.success.read", sources.get(id, userId, userNsRoles));
    }

    @GetMapping("/{id}/base-file")
    @Operation(summary = "预览同步审核基线版本的变更文件")
    public ResponseEntity<InputStreamResource> getBaseFile(
            @PathVariable Long id,
            @RequestParam String path,
            @Parameter(description = "是否按 256 KiB 上限读取预览；缺省 false 保留原读取行为")
            @RequestParam(defaultValue = "false") boolean preview,
            @RequestAttribute("userId") String userId,
            @RequestAttribute(value = "userNsRoles", required = false)
            Map<Long, NamespaceRole> userNsRoles) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(sources.getBaseFile(id, path, userId, userNsRoles, preview)));
    }
}
