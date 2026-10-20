CREATE TABLE github_sync_run (
    id UUID PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('DISCOVERY', 'REPOSITORY_SYNC')),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_github_sync_run_id_namespace UNIQUE (id, namespace_id),
    CONSTRAINT ck_github_sync_run_status CHECK
        (status IN ('QUEUED', 'LEASED', 'RETRY_WAIT', 'PARTIAL', 'ACQUISITION_COMPLETE', 'FAILED'))
);

CREATE TABLE github_sync_task (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    source_id BIGINT,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('DISCOVERY', 'REPOSITORY_SYNC')),
    mode VARCHAR(16) CHECK (mode IN ('FULL', 'HEAD_ONLY')),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    dedupe_key VARCHAR(256) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    snapshot_json JSONB NOT NULL,
    due_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retry_at TIMESTAMPTZ,
    lease_generation BIGINT NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
    lease_token_hash CHAR(64),
    lease_expires_at TIMESTAMPTZ,
    leased_by VARCHAR(128),
    fixed_commit_sha VARCHAR(40),
    path_checkpoint VARCHAR(4096),
    incomplete_subtrees JSONB NOT NULL DEFAULT '[]'::jsonb,
    discovery_checkpoint VARCHAR(4096),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_github_sync_task_status CHECK
        (status IN ('QUEUED', 'LEASED', 'RETRY_WAIT', 'PARTIAL', 'ACQUISITION_COMPLETE', 'FAILED')),
    CONSTRAINT ck_github_sync_task_snapshot CHECK (jsonb_typeof(snapshot_json) = 'object'),
    CONSTRAINT ck_github_sync_task_incomplete_subtrees CHECK (jsonb_typeof(incomplete_subtrees) = 'array'),
    CONSTRAINT ck_github_sync_task_dedupe_key CHECK (length(dedupe_key) > 0),
    CONSTRAINT ck_github_sync_task_shape CHECK
        ((kind = 'DISCOVERY' AND mode IS NULL AND source_id IS NULL)
         OR (kind = 'REPOSITORY_SYNC' AND mode IS NOT NULL)),
    CONSTRAINT fk_github_sync_task_run_namespace FOREIGN KEY (run_id, namespace_id)
        REFERENCES github_sync_run(id, namespace_id),
    CONSTRAINT fk_github_sync_task_source_namespace FOREIGN KEY (source_id, namespace_id)
        REFERENCES github_source_binding(id, namespace_id),
    CONSTRAINT ck_github_sync_task_lease CHECK
        ((status = 'LEASED' AND lease_token_hash IS NOT NULL AND lease_expires_at IS NOT NULL AND leased_by IS NOT NULL)
         OR (status <> 'LEASED' AND lease_token_hash IS NULL AND lease_expires_at IS NULL AND leased_by IS NULL))
);

CREATE UNIQUE INDEX uq_github_sync_task_open_work
    ON github_sync_task (namespace_id, dedupe_key)
    WHERE status IN ('QUEUED', 'LEASED', 'RETRY_WAIT');

CREATE INDEX idx_github_sync_task_claim
    ON github_sync_task (priority DESC, due_at, id)
    WHERE status IN ('QUEUED', 'LEASED', 'RETRY_WAIT');

CREATE TABLE github_sync_path_result (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES github_sync_task(id),
    commit_sha VARCHAR(40) NOT NULL,
    skill_path VARCHAR(1024) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK
        (outcome IN ('ACCEPTED', 'UNCHANGED', 'IGNORED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE')),
    ingestion_id UUID,
    sha256 CHAR(64),
    reason VARCHAR(500),
    retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_sync_path_result UNIQUE (task_id, commit_sha, skill_path)
);
