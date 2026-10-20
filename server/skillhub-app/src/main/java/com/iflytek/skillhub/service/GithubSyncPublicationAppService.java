package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.githubsync.GithubSyncPublicationRepository;
import com.iflytek.skillhub.githubsync.GithubSourceIgnoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;

@Service
public class GithubSyncPublicationAppService {
    private final GithubSyncPublicationRepository publications;
    private final GithubSyncArchiveValidator archives;
    private final SkillPublishService skills;
    private final GithubSourceIgnoreRepository ignores;
    private final AuditLogService audits;

    public GithubSyncPublicationAppService(GithubSyncPublicationRepository publications,
                                           GithubSyncArchiveValidator archives,
                                           SkillPublishService skills,
                                           GithubSourceIgnoreRepository ignores,
                                           AuditLogService audits) {
        this.publications = publications;
        this.archives = archives;
        this.skills = skills;
        this.ignores = ignores;
        this.audits = audits;
    }

    /** One database transaction covers the Skill version, review and path binding. */
    @Transactional(rollbackFor = Exception.class)
    public void process(GithubSyncPublicationRepository.ClaimedPublication claim) throws IOException {
        if (!publications.lockAndVerify(claim, Instant.now())) {
            return;
        }
        var currentTarget = publications.currentTarget(claim);
        if (currentTarget.newerAcquisition()) {
            if (!publications.superseded(claim, Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        var targetClaim = claim.withExpectedSkillId(currentTarget.skillId());
        if (claim.sourceId() == null) {
            if (!publications.blocked(claim, "SOURCE_NOT_BOUND", Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if ("PAUSED".equals(claim.sourceStatus())) {
            if (!publications.retry(claim, "SOURCE_PAUSED", Instant.now().plusSeconds(3600),
                    Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if (ignores.ignoredForSource(claim.sourceId(), claim.skillPath())) {
            if (!publications.ignored(claim, Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        var pathMove = publications.inspectPathMove(targetClaim);
        if (pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.RETRY) {
            if (!publications.retry(claim, "PATH_MOVE_SCAN_INCOMPLETE",
                    Instant.now().plusSeconds(900), Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if (pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.INCOMPLETE) {
            if (!publications.blocked(claim, "PATH_MOVE_SCAN_INCOMPLETE", Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if (pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.STALE) {
            if (!publications.blocked(claim, "PATH_MOVE_STALE_SNAPSHOT", Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if (pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.AMBIGUOUS
                || pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.CANDIDATE
                && ignores.ignoredForSource(claim.sourceId(), pathMove.previousPath())) {
            if (!publications.blocked(claim, "PATH_MOVE_AMBIGUOUS", Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }
        if (pathMove.outcome() == GithubSyncPublicationRepository.PathMoveOutcome.CANDIDATE) {
            if (!publications.movePath(targetClaim, pathMove, Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            audits.record("github-sync-publisher", "AUTO_MOVE_GITHUB_SKILL_PATH", "SKILL",
                    pathMove.skillId(), null, null, null,
                    AuditDetail.builder().put("namespaceId", claim.namespaceId())
                            .put("repositoryId", claim.repositoryId())
                            .put("sourceId", claim.sourceId())
                            .put("ingestionId", claim.id())
                            .put("previousPath", pathMove.previousPath())
                            .put("skillPath", claim.skillPath()).build());
            return;
        }
        if (claim.contentSha256().equals(currentTarget.contentSha256())
                && !currentTarget.retryRequested()) {
            if (!publications.noChange(claim, Instant.now())) {
                throw new IllegalStateException("GitHub publication lease changed");
            }
            return;
        }

        SkillPublishService.PublishResult result = archives.withValidatedEntries(
                claim.objectKey(), claim.sizeBytes(), claim.archiveSha256(), claim.skillPath(),
                claim.contentSha256(), (validation, entries) -> skills.publishGithubSyncFromEntries(
                        claim.namespaceSlug(), entries, SkillVisibility.valueOf(claim.visibility()),
                        claim.id(), validation.contentSha256(), targetClaim.expectedSkillId()));
        if (!publications.readyForReview(claim, result.skillId(), result.version().getId(),
                Instant.now())) {
            throw new IllegalStateException("GitHub publication lease changed");
        }
        publications.recordOutcome(claim, result.skillId(), result.version().getId(),
                result.newSkill(), Instant.now());
    }
}
