ALTER TABLE github_sync_task
    ADD COLUMN last_error_detail VARCHAR(100);

ALTER TABLE github_sync_task
    ADD CONSTRAINT ck_github_sync_task_error_detail
    CHECK (last_error_detail IS NULL OR last_error_detail ~ '^[A-Z0-9_:]{1,100}$');
