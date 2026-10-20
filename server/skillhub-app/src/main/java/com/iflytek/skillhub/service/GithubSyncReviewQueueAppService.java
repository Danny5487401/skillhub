package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.GithubSyncReviewQueueItemResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.ReviewTaskResponse;
import com.iflytek.skillhub.repository.GithubSyncReviewSourceQueryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GithubSyncReviewQueueAppService {
    private final ReviewPortalAppService reviews;
    private final GithubSyncReviewSourceQueryRepository sources;

    public GithubSyncReviewQueueAppService(ReviewPortalAppService reviews,
                                           GithubSyncReviewSourceQueryRepository sources) {
        this.reviews = reviews;
        this.sources = sources;
    }

    public PageResponse<GithubSyncReviewQueueItemResponse> list(String status, Long namespaceId,
            int page, int size, String sortDirection, String userId,
            Map<Long, NamespaceRole> namespaceRoles) {
        PageResponse<ReviewTaskResponse> visible = reviews.listSyncReviews(
                status, namespaceId, page, size, sortDirection, userId, namespaceRoles);
        Map<Long, GithubSyncReviewSourceQueryRepository.QueueSource> metadata = sources.findQueueSources(
                visible.items().stream().map(ReviewTaskResponse::id).toList());
        List<GithubSyncReviewQueueItemResponse> items = visible.items().stream().map(review -> {
            GithubSyncReviewSourceQueryRepository.QueueSource source = metadata.get(review.id());
            if (source == null) {
                throw new IllegalStateException("GitHub sync review has no ingestion source");
            }
            return new GithubSyncReviewQueueItemResponse(review, source.ingestionId(), source.runId(),
                    source.repositoryId(), source.ownerLogin(), source.repositoryName(),
                    source.skillPath(), source.commitSha());
        }).toList();
        return new PageResponse<>(items, visible.total(), visible.page(), visible.size());
    }
}
