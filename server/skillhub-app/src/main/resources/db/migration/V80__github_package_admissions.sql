-- Watermarks have no guessed defaults: the admin configuration flow must set both pairs
-- before Java may grant package acquisition.
CREATE TABLE github_sync_admission_policy (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    global_high INTEGER,
    global_low INTEGER,
    namespace_high INTEGER,
    namespace_low INTEGER,
    global_paused BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_github_sync_global_watermarks CHECK
        ((global_high IS NULL AND global_low IS NULL)
         OR (global_high IS NOT NULL AND global_low IS NOT NULL
             AND global_high > 0 AND global_low >= 0 AND global_low < global_high)),
    CONSTRAINT ck_github_sync_namespace_watermarks CHECK
        ((namespace_high IS NULL AND namespace_low IS NULL)
         OR (namespace_high IS NOT NULL AND namespace_low IS NOT NULL
             AND namespace_high > 0 AND namespace_low >= 0 AND namespace_low < namespace_high))
);

INSERT INTO github_sync_admission_policy (id) VALUES (TRUE);

CREATE TABLE github_sync_namespace_admission_state (
    namespace_id BIGINT PRIMARY KEY REFERENCES namespace(id),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE github_sync_package_admission (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES github_sync_task(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    commit_sha CHAR(40) NOT NULL,
    skill_path VARCHAR(1024) NOT NULL,
    lease_generation BIGINT NOT NULL CHECK (lease_generation > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED', 'ACCEPTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_package_admission_attempt
        UNIQUE (task_id, commit_sha, skill_path, lease_generation)
);

CREATE INDEX idx_github_package_admission_active_coordinate
    ON github_sync_package_admission (namespace_id, repository_id, skill_path)
    WHERE status = 'ACTIVE';
