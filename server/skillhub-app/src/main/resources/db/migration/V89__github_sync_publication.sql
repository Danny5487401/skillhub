-- Each GitHub Skill path has one namespace-local Skill identity. Publication work is leased
-- separately from archive validation so a restarted Java instance can resume it.
ALTER TABLE github_source_binding
    ADD CONSTRAINT uq_github_source_full_coordinate UNIQUE (id, namespace_id, repository_id);

ALTER TABLE skill_version
    ADD COLUMN github_sync_ingestion_id UUID UNIQUE REFERENCES github_sync_package_ingestion(id);

ALTER TABLE skill
    ADD COLUMN github_sync_managed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE github_source_skill_binding (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES github_source_binding(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    skill_path VARCHAR(1024) NOT NULL,
    skill_id BIGINT NOT NULL REFERENCES skill(id),
    latest_content_sha256 CHAR(64) NOT NULL,
    latest_ingestion_id UUID NOT NULL REFERENCES github_sync_package_ingestion(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_github_skill_binding_source_coordinate FOREIGN KEY
        (source_id, namespace_id, repository_id)
        REFERENCES github_source_binding(id, namespace_id, repository_id),
    CONSTRAINT uq_github_skill_binding_path UNIQUE (namespace_id, repository_id, skill_path),
    CONSTRAINT uq_github_skill_binding_skill UNIQUE (namespace_id, skill_id)
);

CREATE INDEX idx_github_skill_binding_content
    ON github_source_skill_binding (source_id, latest_content_sha256);

ALTER TABLE github_sync_package_ingestion
    ADD COLUMN publication_token UUID,
    ADD COLUMN publication_expires_at TIMESTAMPTZ,
    ADD COLUMN publication_retry_at TIMESTAMPTZ,
    ADD COLUMN publication_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publication_attempts >= 0),
    ADD COLUMN published_skill_id BIGINT REFERENCES skill(id),
    ADD COLUMN published_version_id BIGINT REFERENCES skill_version(id),
    ADD CONSTRAINT ck_github_sync_publication_lease CHECK
        ((publication_token IS NULL AND publication_expires_at IS NULL)
         OR (publication_token IS NOT NULL AND publication_expires_at IS NOT NULL));

CREATE INDEX idx_github_sync_publication_claim
    ON github_sync_package_ingestion (publication_retry_at, validated_at, id)
    WHERE status = 'VALIDATED';
