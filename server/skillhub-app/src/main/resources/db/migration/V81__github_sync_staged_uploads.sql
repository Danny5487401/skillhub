CREATE TABLE github_sync_staged_upload (
    id UUID PRIMARY KEY,
    admission_id UUID NOT NULL REFERENCES github_sync_package_admission(id),
    object_key VARCHAR(256) NOT NULL UNIQUE,
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('LOCAL_STREAM', 'S3_PRESIGNED_PUT')),
    ticket_hash CHAR(64),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED'
        CHECK (status IN ('ISSUED', 'UPLOADING', 'UPLOADED', 'FAILED',
                          'ACCEPTED', 'DELETING', 'CLEANED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_github_sync_upload_ticket CHECK
        ((mode = 'LOCAL_STREAM' AND ticket_hash IS NOT NULL)
         OR (mode = 'S3_PRESIGNED_PUT' AND ticket_hash IS NULL))
);

CREATE INDEX idx_github_sync_staged_upload_cleanup
    ON github_sync_staged_upload (status, expires_at)
    WHERE status IN ('ISSUED', 'UPLOADING', 'UPLOADED', 'FAILED', 'DELETING');
