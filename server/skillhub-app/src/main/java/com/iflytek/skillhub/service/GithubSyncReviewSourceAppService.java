package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.ReviewOriginType;
import com.iflytek.skillhub.domain.review.ReviewService;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.GithubSyncReviewSourceResponse;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.repository.GithubSyncReviewSourceQueryRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.io.InputStream;

@Service
public class GithubSyncReviewSourceAppService {
    private final ReviewTaskRepository reviews;
    private final NamespaceRepository namespaces;
    private final ReviewService reviewService;
    private final RbacService rbac;
    private final GithubSyncReviewSourceQueryRepository sources;
    private final SkillQueryService skillQueries;

    public GithubSyncReviewSourceAppService(ReviewTaskRepository reviews,
                                            NamespaceRepository namespaces,
                                            ReviewService reviewService,
                                            RbacService rbac,
                                            GithubSyncReviewSourceQueryRepository sources,
                                            SkillQueryService skillQueries) {
        this.reviews = reviews;
        this.namespaces = namespaces;
        this.reviewService = reviewService;
        this.rbac = rbac;
        this.sources = sources;
        this.skillQueries = skillQueries;
    }

    public GithubSyncReviewSourceResponse get(Long reviewId, String userId,
                                               Map<Long, NamespaceRole> namespaceRoles) {
        ReviewTask review = reviews.findById(reviewId)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found", reviewId));
        if (review.getOriginType() != ReviewOriginType.GITHUB_SYNC) {
            throw new DomainNotFoundException("review_task.not_found", reviewId);
        }
        Namespace namespace = namespaces.findById(review.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", review.getNamespaceId()));
        if (!reviewService.canViewReview(review, userId, namespace.getType(),
                namespaceRoles == null ? Map.of() : namespaceRoles, rbac.getUserRoleCodes(userId))) {
            throw new DomainForbiddenException("review.no_permission");
        }
        return sources.findByReviewId(reviewId)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found", reviewId));
    }

    public InputStream getBaseFile(Long reviewId, String path, String userId,
                                   Map<Long, NamespaceRole> namespaceRoles) {
        return getBaseFile(reviewId, path, userId, namespaceRoles, false);
    }

    public InputStream getBaseFile(Long reviewId, String path, String userId,
                                   Map<Long, NamespaceRole> namespaceRoles, boolean preview) {
        GithubSyncReviewSourceResponse source = get(reviewId, userId, namespaceRoles);
        boolean changedBaseFile = source.baseVersionId() != null && source.fileChanges().stream()
                .anyMatch(file -> file.path().equals(path) && file.oldSha256() != null
                        && file.oldSize() != null && file.oldSize() <= 1024 * 1024);
        if (!changedBaseFile) {
            throw new DomainNotFoundException("review_task.not_found", reviewId);
        }
        return preview
                ? skillQueries.getFilePreviewContentByVersionId(source.baseVersionId(), path)
                : skillQueries.getFileContentByVersionId(source.baseVersionId(), path);
    }
}
