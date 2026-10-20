-- Persist the last successful lease per namespace so a large source queue in one
-- namespace cannot indefinitely hide another namespace's due source tasks.
CREATE TABLE github_sync_claim_cursor (
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('DISCOVERY', 'REPOSITORY_SYNC')),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    last_claimed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (kind, namespace_id)
);
