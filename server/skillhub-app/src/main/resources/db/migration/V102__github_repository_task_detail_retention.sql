-- Repository task and run IDs remain as audit references after their operational
-- details expire. Latest per-path source state is retained separately.
ALTER TABLE github_sync_task ADD COLUMN details_purged_at TIMESTAMPTZ;
ALTER TABLE github_sync_run ADD COLUMN details_purged_at TIMESTAMPTZ;

CREATE INDEX idx_github_sync_repository_detail_cleanup
    ON github_sync_task (completed_at, id)
    WHERE kind = 'REPOSITORY_SYNC'
      AND status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
      AND details_purged_at IS NULL;

CREATE INDEX idx_github_sync_run_detail_cleanup
    ON github_sync_run (finished_at, id)
    WHERE kind = 'REPOSITORY_SYNC'
      AND status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
      AND details_purged_at IS NULL;

CREATE INDEX idx_github_sync_path_skill_task
    ON github_sync_path_result (skill_path, task_id, updated_at DESC, id DESC);
