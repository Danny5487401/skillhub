package com.iflytek.skillhub.domain.namespace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "namespace_invitation_history")
public class NamespaceInvitationHistory {
    @Id
    @Column(name = "id")
    private String id;
    @Column(name = "namespace_id")
    private Long namespaceId;
    @Column(name = "issued_by")
    private String issuedBy;
    @Column(name = "issued_by_name")
    private String issuedByName;
    @Column(name = "issued_at")
    private Instant issuedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "consumed_by")
    private String consumedBy;
    @Column(name = "consumed_by_name")
    private String consumedByName;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "ended_reason")
    private String endedReason;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "legacy")
    private boolean legacy;

    protected NamespaceInvitationHistory() {}

    public NamespaceInvitationHistory(Long namespaceId, String issuer, String issuerName, Instant issuedAt, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.namespaceId = namespaceId;
        this.issuedBy = issuer;
        this.issuedByName = issuerName;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void end(String reason, Instant now) {
        if (consumedAt == null && endedAt == null && expiresAt.isAfter(now)) {
            endedReason = reason;
            endedAt = now;
        }
    }

    public void consume(String userId, String name, Instant now) {
        consumedBy = userId;
        consumedByName = name;
        consumedAt = now;
    }

    public String status(Instant now) {
        if (consumedAt != null) return "USED";
        if (endedReason != null) return endedReason;
        return expiresAt.isAfter(now) ? "ACTIVE" : "EXPIRED";
    }
    public String getId() { return id; }
    public Long getNamespaceId() { return namespaceId; }
    public String getIssuedBy() { return issuedBy; }
    public String getIssuedByName() { return issuedByName; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getConsumedBy() { return consumedBy; }
    public String getConsumedByName() { return consumedByName; }
    public Instant getConsumedAt() { return consumedAt; }
    public String getEndedReason() { return endedReason; }
    public Instant getEndedAt() { return endedAt; }
    public boolean isLegacy() { return legacy; }
}
