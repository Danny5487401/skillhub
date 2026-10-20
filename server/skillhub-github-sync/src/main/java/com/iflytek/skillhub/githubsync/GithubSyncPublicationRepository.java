package com.iflytek.skillhub.githubsync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable handoff from a validated staged package to a Skill version and sync review. */
public interface GithubSyncPublicationRepository {
    Optional<ClaimedPublication> claim(UUID token, Instant now, Instant expiresAt);

    /** Locks the claim row for the publication transaction. */
    boolean lockAndVerify(ClaimedPublication claim, Instant now);

    /** Reads the current path target after the publication transaction has acquired its path lock. */
    CurrentTarget currentTarget(ClaimedPublication claim);

    boolean superseded(ClaimedPublication claim, Instant now);

    /** Returns false if a newer worker owns the lease; callers must roll back their transaction. */
    boolean readyForReview(ClaimedPublication claim, Long skillId, Long versionId, Instant now);

    /** Records actual newly created entities only, in the successful publication transaction. */
    void recordOutcome(ClaimedPublication claim, Long skillId, Long versionId,
                               boolean newSkill, Instant now);

    boolean noChange(ClaimedPublication claim, Instant now);

    /** Marks one rejected path for a fresh acquisition and review. */
    boolean requestRejectedRetry(long namespaceId, long sourceId, String skillPath, Instant now);

    /** Classifies an unbound path against the completed repository snapshot. */
    PathMoveDecision inspectPathMove(ClaimedPublication claim);

    /** Atomically moves the source binding and settles the identical package without a new version. */
    boolean movePath(ClaimedPublication claim, PathMoveDecision decision, Instant now);

    boolean ignored(ClaimedPublication claim, Instant now);

    boolean blocked(ClaimedPublication claim, String reason, Instant now);

    /** Persists the canonical slug when a namespace coordinate needs an administrator decision. */
    boolean blockedSlugConflict(ClaimedPublication claim, String skillSlug, Instant now);

    boolean retry(ClaimedPublication claim, String reason, Instant retryAt, Instant now);

    enum PathMoveOutcome { NONE, RETRY, INCOMPLETE, STALE, AMBIGUOUS, CANDIDATE }

    record CurrentTarget(Long skillId, String contentSha256, boolean retryRequested,
                         boolean newerAcquisition) {}

    record PathMoveDecision(PathMoveOutcome outcome, Long skillId, String previousPath) {
        public static PathMoveDecision of(PathMoveOutcome outcome) {
            return new PathMoveDecision(outcome, null, null);
        }
    }

    record ClaimedPublication(UUID id, UUID token, Long namespaceId, String namespaceSlug,
                              long repositoryId, Long sourceId, String skillPath,
                              String sourceStatus, String visibility, String objectKey, long sizeBytes,
                              String archiveSha256, String contentSha256,
                              Long expectedSkillId, int attempt) {
        public ClaimedPublication withExpectedSkillId(Long skillId) {
            return new ClaimedPublication(id, token, namespaceId, namespaceSlug, repositoryId,
                    sourceId, skillPath, sourceStatus, visibility, objectKey, sizeBytes,
                    archiveSha256, contentSha256, skillId, attempt);
        }
    }
}
