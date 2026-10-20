ALTER TABLE github_discovery_candidate
    ADD COLUMN validation_task_id UUID REFERENCES github_sync_task(id),
    ADD COLUMN next_validation_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN unreachable_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (unreachable_attempts >= 0),
    ADD COLUMN last_error_code VARCHAR(64),
    ADD CONSTRAINT ck_github_candidate_validation_task
        CHECK (status <> 'VALIDATING' OR validation_task_id IS NOT NULL);

CREATE UNIQUE INDEX uq_github_candidate_validation_task
    ON github_discovery_candidate (validation_task_id)
    WHERE validation_task_id IS NOT NULL;

CREATE INDEX idx_github_candidate_validation_due
    ON github_discovery_candidate (next_validation_at, id)
    WHERE status IN ('PENDING', 'REJECTED');
