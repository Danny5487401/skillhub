package com.iflytek.skillhub.githubsync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "github_source_origin", uniqueConstraints =
        @UniqueConstraint(name = "uq_github_source_origin", columnNames = {"source_id", "origin_type", "origin_key"}))
public class GithubSourceOrigin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 32)
    private GithubSourceOriginType originType;

    @Column(name = "origin_key", nullable = false, length = 256)
    private String originKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GithubSourceOrigin() {}

    public GithubSourceOrigin(long sourceId, GithubSourceOriginType originType, String originKey) {
        if (sourceId <= 0 || originType == null || originKey == null || originKey.isBlank()) {
            throw new IllegalArgumentException("source and origin are required");
        }
        this.sourceId = sourceId;
        this.originType = originType;
        this.originKey = originKey;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSourceId() { return sourceId; }
    public GithubSourceOriginType getOriginType() { return originType; }
    public String getOriginKey() { return originKey; }
    public Instant getCreatedAt() { return createdAt; }
}
