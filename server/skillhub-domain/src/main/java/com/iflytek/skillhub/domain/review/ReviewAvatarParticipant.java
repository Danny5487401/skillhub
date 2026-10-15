package com.iflytek.skillhub.domain.review;

/** Image changes participate in the same transaction and decision as the review task. */
public interface ReviewAvatarParticipant {
    void lock(ReviewTask task);
    void validateApproval(ReviewTask task, String comment);
    void decide(ReviewTask task, boolean approved, String actor, String comment);
    void withdraw(ReviewTask task);
}
