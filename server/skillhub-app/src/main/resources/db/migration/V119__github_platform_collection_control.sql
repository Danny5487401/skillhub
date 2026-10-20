CREATE TABLE github_platform_collection_control (
    namespace_id BIGINT PRIMARY KEY REFERENCES namespace(id),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128)
);
INSERT INTO github_platform_collection_control(namespace_id)
SELECT id FROM namespace WHERE slug = 'github' AND type = 'CATALOG';

ALTER TABLE github_sync_task
    ADD COLUMN platform_pause_version BIGINT,
    ADD COLUMN platform_pause_generation BIGINT,
    ADD COLUMN platform_pause_token_hash CHAR(64),
    ADD COLUMN platform_stop_ack_at TIMESTAMPTZ;
