-- A run may contain more than one repository task. Keep its visible status in sync
-- with the persisted task states, including retries and partial completion.
CREATE FUNCTION refresh_github_sync_run_status() RETURNS trigger AS $$
BEGIN
    -- Serialize concurrent task updates for the same run before reading siblings.
    PERFORM 1 FROM github_sync_run WHERE id = NEW.run_id FOR NO KEY UPDATE;

    UPDATE github_sync_run AS run
    SET status = summary.status,
        finished_at = CASE WHEN summary.status IN ('ACQUISITION_COMPLETE', 'PARTIAL', 'FAILED')
                           THEN COALESCE(run.finished_at, CURRENT_TIMESTAMP) ELSE NULL END
    FROM (
        SELECT CASE
            WHEN bool_or(status = 'LEASED') THEN 'LEASED'
            WHEN bool_or(status = 'QUEUED') THEN 'QUEUED'
            WHEN bool_or(status = 'RETRY_WAIT') THEN 'RETRY_WAIT'
            WHEN bool_or(status = 'PARTIAL')
              OR (bool_or(status = 'FAILED') AND bool_or(status = 'ACQUISITION_COMPLETE'))
                THEN 'PARTIAL'
            WHEN bool_and(status = 'FAILED') THEN 'FAILED'
            ELSE 'ACQUISITION_COMPLETE'
        END AS status
        FROM github_sync_task WHERE run_id = NEW.run_id
    ) AS summary
    WHERE run.id = NEW.run_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER github_sync_run_status_after_task
    AFTER INSERT OR UPDATE OF status ON github_sync_task
    FOR EACH ROW EXECUTE FUNCTION refresh_github_sync_run_status();
