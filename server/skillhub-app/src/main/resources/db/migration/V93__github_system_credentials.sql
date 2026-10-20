CREATE TABLE github_system_credential (
    id UUID PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    fingerprint CHAR(16) NOT NULL UNIQUE,
    key_version VARCHAR(40) NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) >= 17),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INVALID', 'DISABLED')),
    rate_limited_until TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_github_credential_label CHECK (length(btrim(label)) > 0)
);

ALTER TABLE github_sync_task
    ADD COLUMN credential_id UUID REFERENCES github_system_credential(id),
    ADD COLUMN credential_fingerprint CHAR(16);

CREATE INDEX idx_github_system_credential_available
    ON github_system_credential (last_used_at ASC NULLS FIRST, id)
    WHERE status = 'ACTIVE';

CREATE TABLE github_system_credential_quota (
    credential_id UUID NOT NULL REFERENCES github_system_credential(id) ON DELETE CASCADE,
    resource VARCHAR(16) NOT NULL CHECK (resource IN ('CORE', 'SEARCH', 'GRAPHQL', 'OTHER')),
    remaining INTEGER NOT NULL CHECK (remaining >= 0),
    reset_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (credential_id, resource)
);
