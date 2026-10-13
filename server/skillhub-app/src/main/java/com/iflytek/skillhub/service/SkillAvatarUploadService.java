package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillAvatarUpload;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.SkillAvatarStatusResponse;
import com.iflytek.skillhub.dto.SkillAvatarUploadResponse;
import com.iflytek.skillhub.infra.jpa.SkillAvatarUploadJpaRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SkillAvatarUploadService {
    private final SkillAvatarUploadJpaRepository uploads;
    private final SkillAvatarImageProcessor images;
    private final ObjectStorageService storage;
    private final SkillAvatarAppService avatars;
    private final ApplicationEventPublisher events;
    private ReviewTaskRepository reviews;

    @Autowired
    public void setReviews(ReviewTaskRepository reviews) { this.reviews = reviews; }


    public SkillAvatarUploadService(SkillAvatarUploadJpaRepository uploads, SkillAvatarImageProcessor images,
            ObjectStorageService storage, SkillAvatarAppService avatars, ApplicationEventPublisher events) {
        this.uploads = uploads;
        this.images = images;
        this.storage = storage;
        this.avatars = avatars;
        this.events = events;
    }

    @Transactional
    public SkillAvatarUploadResponse upload(String actor, byte[] image) {
        requireAuthenticated(actor);
        byte[] normalized = images.normalize(image);
        String id = UUID.randomUUID().toString();
        String key = "skill-avatars/uploads/" + id + ".png";
        storage.putObject(key, new ByteArrayInputStream(normalized), normalized.length, "image/png");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int result) {
                if (result != STATUS_COMMITTED) {
                    storage.deleteObject(key);
                }
            }
        });
        Instant expiresAt = Instant.now().plusSeconds(24 * 60 * 60);
        uploads.saveAndFlush(new SkillAvatarUpload(id, actor, key, sha256(normalized), expiresAt));
        return new SkillAvatarUploadResponse(id, "/api/web/skills/avatar-uploads/" + id + "/image", expiresAt);
    }

    @Transactional(readOnly = true)
    public InputStream preview(String id, String actor) {
        SkillAvatarUpload upload = uploads.findById(id).orElseThrow(() -> notFound());
        requireUploader(upload, actor);
        if (upload.getObjectKey() == null || !upload.getExpiresAt().isAfter(Instant.now())) {
            throw notFound();
        }
        return storage.getObject(upload.getObjectKey());
    }

    @Transactional(readOnly = true)
    public void validateReference(String namespace, String slug, String actor, String id) {
        SkillAvatarUpload upload = uploads.findById(id).orElseThrow(() -> notFound());
        requireUploader(upload, actor);
        if (upload.getCandidateId() != null) {
            avatars.submittedStatus(namespace, slug, actor, upload.getCandidateId());
        } else if (upload.getObjectKey() == null || !upload.getExpiresAt().isAfter(Instant.now())) {
            throw new DomainBadRequestException("error.skill.avatar.uploadExpired");
        } else if (!storage.exists(upload.getObjectKey())) {
            throw new DomainBadRequestException("error.skill.avatar.invalidUpload");
        }
    }

    @Transactional
    public SkillAvatarStatusResponse submit(String namespace, String slug, String actor, String id,
                                            AuditRequestContext context) {
        return submitForAudience(namespace, slug, actor, id, null, context);
    }

    @Transactional
    public SkillAvatarStatusResponse submitForAudience(String namespace, String slug, String actor, String id,
            SkillVisibility audience, AuditRequestContext context) {
        return submitForReview(namespace, slug, actor, id, audience, context, null);
    }

    @Transactional
    public SkillAvatarStatusResponse submitForReview(String namespace, String slug, String actor, String id,
            SkillVisibility audience, AuditRequestContext context, ReviewTask review) {
        // Publication already holds this Skill lock; standalone submission follows the same lock order.
        avatars.lockOwnedSkill(namespace, slug, actor);
        SkillAvatarUpload upload = uploads.findLockedById(id).orElseThrow(() -> notFound());
        requireUploader(upload, actor);
        if (upload.getCandidateId() != null) {
            if (review != null && reviews.findByAvatarCandidateIdAndStatus(upload.getCandidateId(), review.getStatus())
                    .stream().noneMatch(bound -> bound.getId().equals(review.getId()))) {
                throw new DomainConflictException("error.skill.avatar.invalidUpload");
            }
            // Owner and exact Skill binding are rechecked even for a repeated successful submission.
            return avatars.submittedStatus(namespace, slug, actor, upload.getCandidateId());
        }
        if (upload.getObjectKey() == null || !upload.getExpiresAt().isAfter(Instant.now())) {
            throw new DomainBadRequestException("error.skill.avatar.uploadExpired");
        }
        if (!storage.exists(upload.getObjectKey())) {
            throw new DomainBadRequestException("error.skill.avatar.invalidUpload");
        }
        SkillAvatarStatusResponse status = review == null
                ? avatars.submitStored(namespace, slug, actor, upload.getObjectKey(), upload.getContentSha256(), audience, context)
                : avatars.submitStoredForReview(namespace, slug, actor, upload.getObjectKey(), upload.getContentSha256(), audience, context, review);
        upload.consume(status.candidateId());
        if ("PENDING".equals(status.machineStatus()) && "PENDING".equals(status.candidateStatus())) {
            events.publishEvent(new AvatarSubmittedEvent(status.candidateId(), context));
        }
        return status;
    }

    public record AvatarSubmittedEvent(long candidateId, AuditRequestContext context) {}

    private void requireAuthenticated(String actor) {
        if (actor == null) {
            throw new DomainForbiddenException("error.skill.avatar.ownerOnly");
        }
    }
    private void requireUploader(SkillAvatarUpload upload, String actor) {
        requireAuthenticated(actor);
        if (!upload.getUploadedBy().equals(actor)) {
            throw new DomainForbiddenException("error.skill.avatar.ownerOnly");
        }
    }
    private String sha256(byte[] image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private DomainNotFoundException notFound() {
        return new DomainNotFoundException("error.skill.avatar.notFound");
    }
}
