ALTER TABLE review_task ADD COLUMN previous_visibility VARCHAR(20);
ALTER TABLE review_task ADD COLUMN requested_visibility VARCHAR(20);
ALTER TABLE review_task ADD COLUMN visibility_version_id BIGINT;
ALTER TABLE review_task ADD COLUMN visibility_scan_task_id VARCHAR(100);
CREATE INDEX idx_review_task_pending_visibility ON review_task(skill_id, status)
    WHERE requested_visibility IS NOT NULL;
