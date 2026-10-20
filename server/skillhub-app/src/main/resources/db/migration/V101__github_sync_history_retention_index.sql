CREATE INDEX idx_github_sync_discovery_history_cleanup
    ON github_sync_task (completed_at, id)
    WHERE kind = 'DISCOVERY' AND status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED');

CREATE INDEX idx_github_sync_discovery_run_cleanup
    ON github_sync_run (finished_at, id)
    WHERE kind = 'DISCOVERY' AND status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED');
