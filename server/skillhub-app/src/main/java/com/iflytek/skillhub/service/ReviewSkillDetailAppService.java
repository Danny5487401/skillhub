package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.review.ReviewSubjectType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillAvatarCandidate;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.infra.jpa.SkillAvatarCandidateJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.ReviewService;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.ReviewSkillDetailResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.SkillDetailResponse;
import com.iflytek.skillhub.dto.SkillFileResponse;
import com.iflytek.skillhub.dto.SkillLifecycleVersionResponse;
import com.iflytek.skillhub.dto.SkillVersionResponse;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReviewSkillDetailAppService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final NamespaceRepository namespaceRepository;
    private final ReviewService reviewService;
    private final RbacService rbacService;
    private final SkillQueryService skillQueryService;
    private final SkillDownloadService skillDownloadService;
    private final ComplianceSnapshotProjectionService complianceSnapshotProjectionService;
    private SkillRepository skills;
    private SkillAvatarCandidateJpaRepository candidates;

    @Autowired
    public void configureAvatars(SkillRepository skills,
            SkillAvatarCandidateJpaRepository candidates) {
        this.skills = skills; this.candidates = candidates;
    }


    public ReviewSkillDetailAppService(ReviewTaskRepository reviewTaskRepository,
                                       NamespaceRepository namespaceRepository,
                                       ReviewService reviewService,
                                       RbacService rbacService,
                                       SkillQueryService skillQueryService,
                                       SkillDownloadService skillDownloadService,
                                       ComplianceSnapshotProjectionService complianceSnapshotProjectionService) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.namespaceRepository = namespaceRepository;
        this.reviewService = reviewService;
        this.rbacService = rbacService;
        this.skillQueryService = skillQueryService;
        this.skillDownloadService = skillDownloadService;
        this.complianceSnapshotProjectionService = complianceSnapshotProjectionService;
    }

    public ReviewSkillDetailResponse getReviewSkillDetail(Long reviewId,
                                                          String userId,
                                                          Map<Long, NamespaceRole> userNsRoles) {
        ReviewAccessContext context = loadAuthorizedContext(reviewId, userId, userNsRoles);
        if (context.task().getSubjectType() == ReviewSubjectType.SKILL_METADATA) {
            return metadataDetail(context);
        }
        SkillQueryService.ReviewSkillSnapshotDTO snapshot =
                skillQueryService.getReviewSkillSnapshot(context.task().getSkillVersionId());

        SkillDetailResponse skill = new SkillDetailResponse(
                snapshot.skill().getId(),
                snapshot.skill().getSlug(),
                snapshot.skill().getDisplayName(),
                snapshot.skill().getOwnerId(),
                snapshot.ownerDisplayName(),
                snapshot.skill().getSummary(),
                snapshot.skill().getVisibility().name(),
                snapshot.skill().getStatus().name(),
                snapshot.skill().getDownloadCount(),
                snapshot.skill().getStarCount(),
                snapshot.skill().getSubscriptionCount(),
                snapshot.skill().getRatingAvg(),
                snapshot.skill().getRatingCount(),
                snapshot.skill().isHidden(),
                context.namespace().getSlug(),
                List.of(),
                false,
                false,
                false,
                false,
                toLifecycleVersion(snapshot.activeVersion()),
                snapshot.publishedVersion() != null ? toLifecycleVersion(snapshot.publishedVersion()) : null,
                toLifecycleVersion(snapshot.activeVersion()),
                null,
                "REVIEW_TASK",
                List.of(),
                new PageResponse<>(List.of(), 0, 0, 20),
                SkillAvatarUrls.current(snapshot.skill(), context.namespace().getSlug())
        );

        List<SkillVersionResponse> versions = snapshot.versions().stream()
                .map(version -> new SkillVersionResponse(
                        version.getId(),
                        version.getVersion(),
                        version.getStatus().name(),
                        version.getChangelog(),
                        version.getFileCount(),
                        version.getTotalSize(),
                        version.getPublishedAt(),
                        version.getId().equals(snapshot.activeVersion().getId())
                                || skillQueryService.isDownloadAvailable(version),
                        complianceSnapshotProjectionService.fromParsedMetadataJson(version.getParsedMetadataJson())
                ))
                .toList();

        List<SkillFileResponse> files = snapshot.files().stream()
                .map(this::toFileResponse)
                .toList();

        return new ReviewSkillDetailResponse(
                skill,
                versions,
                files,
                snapshot.documentationPath(),
                snapshot.documentationContent(),
                "/api/v1/reviews/" + reviewId + "/download",
                snapshot.activeVersion().getVersion(),
                avatarChange(context)
        );
    }

    public SkillDownloadService.DownloadResult downloadReviewPackage(Long reviewId,
                                                                    String userId,
                                                                    Map<Long, NamespaceRole> userNsRoles) {
        ReviewAccessContext context = loadAuthorizedContext(reviewId, userId, userNsRoles);
        if (context.task().getSkillVersionId() == null) {
            throw new DomainBadRequestException("review.metadata.no_package");
        }
        SkillQueryService.ReviewSkillSnapshotDTO snapshot =
                skillQueryService.getReviewSkillSnapshot(context.task().getSkillVersionId());
        return skillDownloadService.downloadReviewVersion(snapshot.skill(), snapshot.activeVersion());
    }

    /**
     * Reads a single file's content from the review's bound skill version.
     * Reuses the existing review authorization context to ensure only
     * authorized reviewers can access the file.
     */
    public InputStream getReviewFileContent(Long reviewId,
                                            String filePath,
                                            String userId,
                                            Map<Long, NamespaceRole> userNsRoles) {
        ReviewAccessContext context = loadAuthorizedContext(reviewId, userId, userNsRoles);
        if (context.task().getSkillVersionId() == null) {
            throw new DomainBadRequestException("review.metadata.no_package");
        }
        return skillQueryService.getFileContentByVersionId(
                context.task().getSkillVersionId(),
                filePath
        );
    }

    private ReviewSkillDetailResponse.AvatarChange avatarChange(ReviewAccessContext context) {
        ReviewTask task = context.task();
        if (task.getAvatarCandidateId() == null && !task.isAvatarRemoval()) { return null; }
        var skill = skills.findById(task.getSkillId()).orElseThrow(() -> new DomainNotFoundException("skill.not_found", task.getSkillId()));
        var candidate = task.getAvatarCandidateId() == null ? null : candidates.findById(task.getAvatarCandidateId()).orElse(null);
        boolean current = candidate != null && candidate.getStatus() == SkillAvatarCandidate.Status.PENDING
                && java.util.Objects.equals(skill.getPendingAvatarId(), candidate.getId());
        boolean removalReady = task.isAvatarRemoval()
                && java.util.Objects.equals(skill.getActiveAvatarId(), task.getAvatarRemovalActiveId())
                && java.util.Objects.equals(skill.getPendingAvatarId(), task.getAvatarRemovalPendingId());
        boolean ready = removalReady || !task.isAvatarRemoval() && current && (!candidate.isMachineEnabled()
                || candidate.getMachineStatus() == SkillAvatarCandidate.MachineStatus.PASSED
                || candidate.getMachineStatus() == SkillAvatarCandidate.MachineStatus.BLOCKED
                    && (candidate.isManualEnabled() || candidate.getReviewStatus() == SkillAvatarCandidate.ReviewStatus.APPEAL_PENDING));
        return new ReviewSkillDetailResponse.AvatarChange(task.getAvatarCandidateId(),
                SkillAvatarUrls.current(skill, context.namespace().getSlug()),
                candidate == null || candidate.getObjectKey() == null ? null : SkillAvatarUrls.candidate(context.namespace().getSlug(), skill, candidate.getId()),
                candidate == null ? null : candidate.getMachineStatus().name(),
                candidate == null ? null : candidate.getReviewStatus().name(), ready, task.isAvatarRemoval());
    }

    private ReviewSkillDetailResponse metadataDetail(ReviewAccessContext context) {
        var skill = skills.findById(context.task().getSkillId()).orElseThrow(() -> new DomainNotFoundException("skill.not_found", context.task().getSkillId()));
        SkillDetailResponse detail = new SkillDetailResponse(skill.getId(), skill.getSlug(), skill.getDisplayName(),
                skill.getOwnerId(), null, skill.getSummary(), skill.getVisibility().name(), skill.getStatus().name(),
                skill.getDownloadCount(), skill.getStarCount(), skill.getSubscriptionCount(), skill.getRatingAvg(),
                skill.getRatingCount(), skill.isHidden(), context.namespace().getSlug(), List.of(), false, false,
                false, false, null, null, null, null, "REVIEW_TASK", List.of(), new PageResponse<>(List.of(), 0, 0, 20),
                SkillAvatarUrls.current(skill, context.namespace().getSlug()));
        return new ReviewSkillDetailResponse(detail, List.of(), List.of(), null, null, null, null, avatarChange(context));
    }

    private ReviewAccessContext loadAuthorizedContext(Long reviewId,
                                                      String userId,
                                                      Map<Long, NamespaceRole> userNsRoles) {
        ReviewTask task = reviewTaskRepository.findById(reviewId)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found", reviewId));
        Namespace namespace = namespaceRepository.findById(task.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", task.getNamespaceId()));
        Map<Long, NamespaceRole> namespaceRoles = userNsRoles != null ? userNsRoles : Map.of();
        Set<String> platformRoles = rbacService.getUserRoleCodes(userId);
        if (!reviewService.canViewReview(task, userId, namespace.getType(), namespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("review.no_permission");
        }
        return new ReviewAccessContext(task, namespace);
    }

    private SkillFileResponse toFileResponse(SkillFile file) {
        return new SkillFileResponse(
                file.getId(),
                file.getFilePath(),
                file.getFileSize(),
                file.getContentType(),
                file.getSha256()
        );
    }

    private SkillLifecycleVersionResponse toLifecycleVersion(SkillVersion version) {
        return new SkillLifecycleVersionResponse(version.getId(), version.getVersion(), version.getStatus().name());
    }

    private record ReviewAccessContext(ReviewTask task, Namespace namespace) {}
}
