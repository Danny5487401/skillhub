CREATE TABLE namespace_invitation (
    namespace_id BIGINT PRIMARY KEY REFERENCES namespace(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_by VARCHAR(128) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_by VARCHAR(128),
    consumed_at TIMESTAMPTZ
);
