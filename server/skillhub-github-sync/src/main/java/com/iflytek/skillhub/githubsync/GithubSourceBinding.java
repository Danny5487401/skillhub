package com.iflytek.skillhub.githubsync;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "github_source_binding", uniqueConstraints =
        @UniqueConstraint(name = "uq_github_source_namespace_repository", columnNames = {"namespace_id", "repository_id"}))
public class GithubSourceBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "owner_login", nullable = false, length = 128)
    private String ownerLogin;

    @Column(name = "repository_name", nullable = false, length = 256)
    private String repositoryName;

    @Column(name = "default_branch", length = 256)
    private String defaultBranch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkillVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GithubSourceStatus status = GithubSourceStatus.ACTIVE;

    @Column(name = "last_observed_commit", length = 40)
    private String lastObservedCommit;

    @Column(name = "last_fully_processed_commit", length = 40)
    private String lastFullyProcessedCommit;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "unreachable_attempts", nullable = false)
    private int unreachableAttempts;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GithubSourceBinding() {}

    public GithubSourceBinding(long namespaceId, long repositoryId, String ownerLogin,
                               String repositoryName, SkillVisibility visibility) {
        if (namespaceId <= 0 || repositoryId <= 0) {
            throw new IllegalArgumentException("namespace and repository IDs must be positive");
        }
        if (ownerLogin == null || ownerLogin.isBlank() || repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repository owner and name are required");
        }
        if (visibility != SkillVisibility.PUBLIC && visibility != SkillVisibility.NAMESPACE_ONLY) {
            throw new IllegalArgumentException("GitHub source visibility must require review");
        }
        this.namespaceId = namespaceId;
        this.repositoryId = repositoryId;
        this.ownerLogin = ownerLogin;
        this.repositoryName = repositoryName;
        this.visibility = visibility;
    }

    public void updateRepositoryLocation(String ownerLogin, String repositoryName, String defaultBranch) {
        if (ownerLogin == null || ownerLogin.isBlank() || repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repository owner and name are required");
        }
        this.ownerLogin = ownerLogin;
        this.repositoryName = repositoryName;
        this.defaultBranch = defaultBranch;
    }

    public void pause() {
        this.status = GithubSourceStatus.PAUSED;
    }

    public void resume() {
        this.status = GithubSourceStatus.ACTIVE;
        this.unreachableAttempts = 0;
        this.lastErrorCode = null;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getNamespaceId() { return namespaceId; }
    public Long getRepositoryId() { return repositoryId; }
    public String getOwnerLogin() { return ownerLogin; }
    public String getRepositoryName() { return repositoryName; }
    public String getDefaultBranch() { return defaultBranch; }
    public SkillVisibility getVisibility() { return visibility; }
    public GithubSourceStatus getStatus() { return status; }
    public String getLastObservedCommit() { return lastObservedCommit; }
    public String getLastFullyProcessedCommit() { return lastFullyProcessedCommit; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public int getUnreachableAttempts() { return unreachableAttempts; }
    public String getLastErrorCode() { return lastErrorCode; }
}
