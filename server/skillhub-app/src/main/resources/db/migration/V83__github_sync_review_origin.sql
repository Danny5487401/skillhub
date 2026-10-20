ALTER TABLE review_task
    ADD COLUMN origin_type VARCHAR(24) NOT NULL DEFAULT 'USER',
    ADD COLUMN github_sync_ingestion_id UUID REFERENCES github_sync_package_ingestion(id);

ALTER TABLE review_task
    ADD CONSTRAINT ck_review_task_origin_type
        CHECK (origin_type IN ('USER', 'GITHUB_SYNC')),
    ADD CONSTRAINT ck_review_task_github_sync_origin
        CHECK ((origin_type = 'USER' AND github_sync_ingestion_id IS NULL)
            OR (origin_type = 'GITHUB_SYNC' AND github_sync_ingestion_id IS NOT NULL
                AND subject_type = 'SKILL_VERSION'));

CREATE UNIQUE INDEX uq_review_task_github_sync_ingestion
    ON review_task (github_sync_ingestion_id)
    WHERE github_sync_ingestion_id IS NOT NULL;

CREATE INDEX idx_review_task_origin_status
    ON review_task (origin_type, status, submitted_at DESC, id DESC);
