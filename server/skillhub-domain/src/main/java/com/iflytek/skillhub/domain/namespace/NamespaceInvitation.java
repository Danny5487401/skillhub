package com.iflytek.skillhub.domain.namespace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "namespace_invitation")
public class NamespaceInvitation {
    @Id
    @Column(name = "namespace_id")
    private Long namespaceId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_by", nullable = false, length = 128)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_by", length = 128)
    private String consumedBy;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "history_id", nullable = false)
    private String historyId;

    public String getHistoryId() { return historyId; }
    public void setHistoryId(String historyId) { this.historyId = historyId; }

    protected NamespaceInvitation() {}

    public NamespaceInvitation(Long namespaceId, String tokenHash, String issuedBy, Instant issuedAt, Instant expiresAt) {
        this.namespaceId = namespaceId;
        this.tokenHash = tokenHash;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public Long getNamespaceId() { return namespaceId; }
    public String getTokenHash() { return tokenHash; }
    public String getIssuedBy() { return issuedBy; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getConsumedBy() { return consumedBy; }
    public Instant getConsumedAt() { return consumedAt; }
    public void replace(String hash, String issuer, Instant now) {
        this.tokenHash = hash;
        this.issuedBy = issuer;
        this.issuedAt = now;
        this.expiresAt = now.plusSeconds(86400);
        this.consumedBy = null;
        this.consumedAt = null;
    }
    public void consume(String userId, Instant now) {
        this.consumedBy = userId;
        this.consumedAt = now;
    }
}
