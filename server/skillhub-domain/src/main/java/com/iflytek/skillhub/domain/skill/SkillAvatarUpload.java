package com.iflytek.skillhub.domain.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Private, uploader-owned image awaiting a successful skill submission. */
@Entity
@Table(name = "skill_avatar_upload")
public class SkillAvatarUpload {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "uploaded_by", nullable = false, length = 128)
    private String uploadedBy;
    @Column(name = "object_key", length = 512)
    private String objectKey;
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "candidate_id")
    private Long candidateId;

    protected SkillAvatarUpload() {}
    public SkillAvatarUpload(String id, String uploadedBy, String objectKey, String sha256, Instant expiresAt) {
        this.id = id;
        this.uploadedBy = uploadedBy;
        this.objectKey = objectKey;
        this.contentSha256 = sha256;
        this.expiresAt = expiresAt;
    }
    public String getId() { return id; }
    public String getUploadedBy() { return uploadedBy; }
    public String getObjectKey() { return objectKey; }
    public String getContentSha256() { return contentSha256; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getCandidateId() { return candidateId; }
    public void consume(Long candidateId) { this.candidateId = candidateId; }
    public void clearObjectKey() { objectKey = null; }
}
