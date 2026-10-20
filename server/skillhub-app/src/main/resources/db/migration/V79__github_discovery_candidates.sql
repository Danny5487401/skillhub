CREATE TABLE github_discovery_candidate (
    id BIGSERIAL PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    owner_login VARCHAR(128) NOT NULL,
    repository_name VARCHAR(256) NOT NULL,
    default_branch VARCHAR(256),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_discovery_candidate UNIQUE (namespace_id, repository_id),
    CONSTRAINT ck_github_discovery_candidate_status CHECK
        (status IN ('PENDING', 'VALIDATING', 'BOUND', 'REJECTED', 'UNREACHABLE'))
);

CREATE INDEX idx_github_discovery_candidate_pending
    ON github_discovery_candidate (namespace_id, first_seen_at, id)
    WHERE status = 'PENDING';

CREATE TABLE github_discovery_task_hit (
    task_id UUID NOT NULL REFERENCES github_sync_task(id) ON DELETE CASCADE,
    repository_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL REFERENCES github_discovery_candidate(id),
    lease_generation BIGINT NOT NULL CHECK (lease_generation > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, repository_id)
);

CREATE TABLE github_discovery_batch (
    task_id UUID NOT NULL REFERENCES github_sync_task(id) ON DELETE CASCADE,
    checkpoint VARCHAR(4096) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    deferred_count INTEGER NOT NULL DEFAULT 0 CHECK (deferred_count >= 0),
    deferral_reason VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, checkpoint),
    CONSTRAINT ck_github_discovery_batch_checkpoint CHECK (length(checkpoint) > 0)
);
