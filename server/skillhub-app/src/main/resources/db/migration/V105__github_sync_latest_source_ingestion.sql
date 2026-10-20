CREATE INDEX idx_github_sync_ingestion_latest_source_path
    ON github_sync_package_ingestion
    (namespace_id, repository_id, skill_path, created_at DESC, id DESC)
    INCLUDE (status);
