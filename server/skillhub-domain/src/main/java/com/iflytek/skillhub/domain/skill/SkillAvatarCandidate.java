package com.iflytek.skillhub.domain.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "skill_avatar_candidate")
public class SkillAvatarCandidate {
    public enum MachineStatus { SKIPPED, PENDING, PROCESSING, PASSED, BLOCKED, ERROR }
    public enum ReviewStatus { NOT_REQUIRED, PENDING, APPROVED, REJECTED, APPEAL_PENDING }
    public enum Status { PENDING, ACTIVE, REJECTED, SUPERSEDED, REMOVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "skill_id", nullable = false)
    private Long skillId;
    @Column(name = "submitted_by", nullable = false, length = 128)
    private String submittedBy;
    @Column(name = "object_key", length = 512)
    private String objectKey;
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SkillVisibility audience;
    @Column(name = "machine_enabled", nullable = false)
    private boolean machineEnabled;
    @Column(name = "manual_enabled", nullable = false)
    private boolean manualEnabled;
    @Column(name = "machine_provider", length = 32)
    private String machineProvider;
    @Column(name = "machine_service_id", length = 128)
    private String machineServiceId;
    @Column(name = "machine_api_version", length = 64)
    private String machineApiVersion;
    @Column(name = "machine_policy_revision", length = 256)
    private String machinePolicyRevision;
    @Column(name = "machine_model_version", length = 128)
    private String machineModelVersion;
    @Column(name = "review_bound", nullable = false)
    private boolean reviewBound;
    @Column(name = "scope_review_version_id")
    private Long scopeReviewVersionId;
    @Column(name = "scope_review", nullable = false)
    private boolean scopeReview;
    @Column(name = "machine_attempt_id", length = 36)
    private String machineAttemptId;
    @Column(name = "machine_lease_until")
    private Instant machineLeaseUntil;
    @Column(name = "machine_next_retry_at")
    private Instant machineNextRetryAt;
    @Column(name = "machine_attempt_count", nullable = false)
    private int machineAttemptCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "machine_status", nullable = false, length = 32)
    private MachineStatus machineStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
    @Column(name = "review_reason", columnDefinition = "TEXT")
    private String reviewReason;
    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "discard_at")
    private Instant discardAt;

    protected SkillAvatarCandidate() {}

    public SkillAvatarCandidate(Long skillId, String submittedBy, String objectKey, String contentSha256,
                                SkillVisibility audience, boolean machineEnabled, boolean manualEnabled,
                                String machineProvider) {
        this.skillId = skillId;
        this.submittedBy = submittedBy;
        this.objectKey = objectKey;
        this.contentSha256 = contentSha256;
        this.audience = audience;
        this.machineEnabled = machineEnabled;
        this.manualEnabled = manualEnabled;
        this.machineProvider = machineProvider;
        this.machineStatus = machineEnabled ? MachineStatus.PENDING : MachineStatus.SKIPPED;
        this.reviewStatus = manualEnabled ? ReviewStatus.PENDING : ReviewStatus.NOT_REQUIRED;
        this.status = Status.PENDING;
    }

    @PrePersist
    void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public String getMachineServiceId() { return machineServiceId; }
    public String getMachineApiVersion() { return machineApiVersion; }
    public String getMachinePolicyRevision() { return machinePolicyRevision; }
    public String getMachineModelVersion() { return machineModelVersion; }

    /** Latest actual attempt context; previous attempts remain in the durable audit log. */
    public void recordMachineContext(String serviceId, String apiVersion, String policyRevision, String modelVersion) {
        machineServiceId = serviceId;
        machineApiVersion = apiVersion;
        machinePolicyRevision = policyRevision;
        machineModelVersion = modelVersion;
    }

    public boolean isReviewBound() { return reviewBound; }
    public void bindReview() { reviewBound = true; }
    public void unifiedDecision(boolean approved, String actor, String reason) {
        if (status != Status.PENDING) { throw new IllegalStateException("Avatar candidate is not pending"); }
        reviewedBy = actor;
        reviewReason = reason;
        reviewedAt = Instant.now();
        reviewStatus = approved ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        if (!approved) { status = Status.REJECTED; discardAt = Instant.now().plusSeconds(30L * 24 * 60 * 60); }
    }

    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public String getSubmittedBy() { return submittedBy; }
    public String getObjectKey() { return objectKey; }
    public String getContentSha256() { return contentSha256; }
    public SkillVisibility getAudience() { return audience; }
    public boolean isMachineEnabled() { return machineEnabled; }
    public boolean isManualEnabled() { return manualEnabled; }
    public String getMachineProvider() { return machineProvider; }
    public Long getScopeReviewVersionId() { return scopeReviewVersionId; }
    public boolean isScopeReview() { return scopeReview; }
    public Instant getMachineLeaseUntil() { return machineLeaseUntil; }
    public Instant getMachineNextRetryAt() { return machineNextRetryAt; }
    public int getMachineAttemptCount() { return machineAttemptCount; }
    public MachineStatus getMachineStatus() { return machineStatus; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public Status getStatus() { return status; }
    public String getReviewReason() { return reviewReason; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDiscardAt() { return discardAt; }

    public void markScopeReviewVersion(Long versionId) { scopeReviewVersionId = versionId; scopeReview = true; }

    public boolean canRetryMachine(Instant now) {
        return status == Status.PENDING && machineEnabled
                && (machineStatus == MachineStatus.ERROR
                        && (machineNextRetryAt == null || !machineNextRetryAt.isAfter(now))
                    || machineStatus == MachineStatus.PROCESSING
                        && machineLeaseUntil != null && !machineLeaseUntil.isAfter(now));
    }

    public boolean claimMachineAttempt(String attemptId, Instant now, Instant leaseUntil) {
        if (status != Status.PENDING || !machineEnabled
                || machineStatus != MachineStatus.PENDING && !canRetryMachine(now)) {
            return false;
        }
        machineStatus = MachineStatus.PROCESSING;
        machineAttemptId = attemptId;
        machineLeaseUntil = leaseUntil;
        machineNextRetryAt = null;
        machineAttemptCount++;
        return true;
    }

    public boolean completeMachineAttempt(String attemptId, MachineStatus result) {
        if (status != Status.PENDING || machineStatus != MachineStatus.PROCESSING
                || !attemptId.equals(machineAttemptId)
                || result == MachineStatus.PENDING || result == MachineStatus.PROCESSING
                || result == MachineStatus.SKIPPED) {
            return false;
        }
        machineStatus = result;
        machineAttemptId = null;
        machineLeaseUntil = null;
        machineNextRetryAt = result == MachineStatus.ERROR ? Instant.now().plusSeconds(3600) : null;
        return true;
    }

    public void machineResult(MachineStatus result) {
        if (status != Status.PENDING || !machineEnabled || result == MachineStatus.SKIPPED
                || result == MachineStatus.PENDING || result == MachineStatus.PROCESSING) {
            throw new IllegalStateException("Avatar candidate cannot accept this machine result");
        }
        machineStatus = result;
    }

    public void appeal() {
        if (status != Status.PENDING || machineStatus != MachineStatus.BLOCKED || manualEnabled) {
            throw new IllegalStateException("Avatar candidate cannot be appealed");
        }
        reviewStatus = ReviewStatus.APPEAL_PENDING;
    }

    public void approve(String actor, String reason) {
        if (status != Status.PENDING || !(reviewStatus == ReviewStatus.PENDING || reviewStatus == ReviewStatus.APPEAL_PENDING)) {
            throw new IllegalStateException("Avatar candidate is not reviewable");
        }
        reviewedBy = actor;
        reviewReason = reason;
        reviewedAt = Instant.now();
        reviewStatus = ReviewStatus.APPROVED;
    }

    public void reject(String actor, String reason) {
        approve(actor, reason);
        reviewStatus = ReviewStatus.REJECTED;
        status = Status.REJECTED;
        discardAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
    }

    public void activate() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Avatar candidate is not pending");
        }
        status = Status.ACTIVE;
    }

    public void supersede() {
        if (status == Status.PENDING || status == Status.ACTIVE) {
            status = Status.SUPERSEDED;
            discardAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
        }
    }

    public void remove() {
        if (status == Status.PENDING || status == Status.ACTIVE) {
            status = Status.REMOVED;
            discardAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
        }
    }

    public void clearObjectKey() { objectKey = null; }
}
