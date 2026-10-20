CREATE INDEX idx_github_sync_task_source_recent
    ON github_sync_task (source_id, completed_at DESC, id DESC)
    WHERE source_id IS NOT NULL AND status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED');
