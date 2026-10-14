CREATE TABLE namespace_invitation_history (
    id VARCHAR(36) PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
    issued_by VARCHAR(128) NOT NULL,
    issued_by_name VARCHAR(255),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_by VARCHAR(128),
    consumed_by_name VARCHAR(255),
    consumed_at TIMESTAMPTZ,
    ended_reason VARCHAR(16),
    ended_at TIMESTAMPTZ,
    legacy BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_namespace_invitation_history_page
    ON namespace_invitation_history(namespace_id, issued_at DESC, id DESC);
ALTER TABLE namespace_invitation ADD COLUMN history_id VARCHAR(36);
UPDATE namespace_invitation SET history_id = gen_random_uuid()::text;
INSERT INTO namespace_invitation_history
    (id, namespace_id, issued_by, issued_at, expires_at, consumed_by, consumed_at, legacy)
SELECT history_id, namespace_id, issued_by, issued_at, expires_at, consumed_by, consumed_at, TRUE
FROM namespace_invitation;
ALTER TABLE namespace_invitation ALTER COLUMN history_id SET NOT NULL;
ALTER TABLE namespace_invitation ADD CONSTRAINT fk_namespace_invitation_history
    FOREIGN KEY (history_id) REFERENCES namespace_invitation_history(id);
