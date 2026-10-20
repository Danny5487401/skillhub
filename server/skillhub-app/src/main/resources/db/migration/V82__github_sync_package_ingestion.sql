CREATE TABLE github_sync_package_ingestion (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES github_sync_task(id),
    admission_id UUID NOT NULL REFERENCES github_sync_package_admission(id),
    upload_id UUID NOT NULL UNIQUE REFERENCES github_sync_staged_upload(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    commit_sha CHAR(40) NOT NULL,
    skill_path VARCHAR(1024) NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED' CHECK
        (status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT', 'READY_FOR_REVIEW',
                    'VALIDATED', 'NO_CHANGE', 'BLOCKED', 'FAILED',
                    'APPROVED', 'REJECTED', 'SUPERSEDED')),
    validation_token UUID,
    validation_expires_at TIMESTAMPTZ,
    validation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (validation_attempts >= 0),
    validated_at TIMESTAMPTZ,
    content_sha256 CHAR(64),
    license_evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_warning_count INTEGER NOT NULL DEFAULT 0 CHECK (validation_warning_count >= 0),
    object_cleanup_claimed_at TIMESTAMPTZ,
    object_cleaned_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_github_sync_license_evidence_json CHECK (jsonb_typeof(license_evidence_json) = 'object'),
    CONSTRAINT ck_github_sync_ingestion_validation_lease CHECK
        ((status = 'VALIDATING' AND validation_token IS NOT NULL AND validation_expires_at IS NOT NULL)
         OR (status <> 'VALIDATING' AND validation_token IS NULL AND validation_expires_at IS NULL)),
    CONSTRAINT uq_github_sync_ingestion_path UNIQUE (task_id, commit_sha, skill_path)
);

CREATE INDEX idx_github_sync_ingestion_unresolved_coordinate
    ON github_sync_package_ingestion (namespace_id, repository_id, skill_path)
    WHERE status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT', 'VALIDATED', 'READY_FOR_REVIEW', 'BLOCKED');

CREATE INDEX idx_github_sync_ingestion_validation_claim
    ON github_sync_package_ingestion (created_at, id)
    WHERE status IN ('RECEIVED', 'VALIDATING', 'RETRY_WAIT');

CREATE INDEX idx_github_sync_ingestion_object_cleanup
    ON github_sync_package_ingestion (updated_at, id)
    WHERE status IN ('FAILED', 'NO_CHANGE', 'READY_FOR_REVIEW',
                     'APPROVED', 'REJECTED', 'SUPERSEDED')
      AND object_cleaned_at IS NULL;
