CREATE TABLE github_source_binding (
    id BIGSERIAL PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    owner_login VARCHAR(128) NOT NULL,
    repository_name VARCHAR(256) NOT NULL,
    default_branch VARCHAR(256),
    visibility VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_observed_commit VARCHAR(40),
    last_fully_processed_commit VARCHAR(40),
    next_check_at TIMESTAMPTZ,
    unreachable_attempts INTEGER NOT NULL DEFAULT 0 CHECK (unreachable_attempts >= 0),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_source_namespace_repository UNIQUE (namespace_id, repository_id),
    CONSTRAINT uq_github_source_id_namespace UNIQUE (id, namespace_id),
    CONSTRAINT ck_github_source_status CHECK (status IN ('ACTIVE', 'PAUSED', 'UNREACHABLE')),
    CONSTRAINT ck_github_source_visibility CHECK (visibility IN ('PUBLIC', 'NAMESPACE_ONLY'))
);

CREATE INDEX idx_github_source_due
    ON github_source_binding (next_check_at, id)
    WHERE status IN ('ACTIVE', 'UNREACHABLE');

CREATE TABLE github_source_origin (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES github_source_binding(id),
    origin_type VARCHAR(32) NOT NULL,
    origin_key VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_source_origin UNIQUE (source_id, origin_type, origin_key),
    CONSTRAINT ck_github_source_origin_type CHECK
        (origin_type IN ('SEARCH_RULE', 'CURATED_REPOSITORY', 'CURATED_OWNER', 'TEAM_CONFIG'))
);
