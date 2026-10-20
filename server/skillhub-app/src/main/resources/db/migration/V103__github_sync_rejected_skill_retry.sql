ALTER TABLE github_source_skill_binding
    ADD COLUMN retry_requested_at TIMESTAMPTZ,
    ADD COLUMN retry_scheduled_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_github_skill_retry_schedule CHECK
        (retry_scheduled_at IS NULL OR retry_requested_at IS NOT NULL);

CREATE INDEX idx_github_skill_binding_retry_requested
    ON github_source_skill_binding (source_id)
    WHERE retry_requested_at IS NOT NULL AND retry_scheduled_at IS NULL;
