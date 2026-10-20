CREATE INDEX idx_github_source_namespace_recent
    ON github_source_binding (namespace_id, id DESC);

CREATE INDEX idx_github_sync_task_source_created
    ON github_sync_task (source_id, created_at DESC, id DESC)
    WHERE source_id IS NOT NULL;

CREATE INDEX idx_github_sync_task_run
    ON github_sync_task (run_id);
