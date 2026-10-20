-- An administrator may reserve an existing Skill identity before the blocked package is retried.
-- These pointers become non-null when publication creates the first synced version.
ALTER TABLE github_source_skill_binding
    ALTER COLUMN latest_content_sha256 DROP NOT NULL,
    ALTER COLUMN latest_ingestion_id DROP NOT NULL;

ALTER TABLE github_sync_package_ingestion
    ADD COLUMN target_skill_slug VARCHAR(128);
