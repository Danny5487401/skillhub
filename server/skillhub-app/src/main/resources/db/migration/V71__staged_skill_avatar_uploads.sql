CREATE TABLE skill_avatar_upload (
    id VARCHAR(36) PRIMARY KEY,
    uploaded_by VARCHAR(128) NOT NULL,
    object_key VARCHAR(512),
    content_sha256 VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    candidate_id BIGINT
);
CREATE INDEX idx_avatar_upload_expiry ON skill_avatar_upload(expires_at) WHERE candidate_id IS NULL AND object_key IS NOT NULL;
