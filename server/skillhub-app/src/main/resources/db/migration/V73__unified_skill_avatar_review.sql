ALTER TABLE review_task ADD COLUMN avatar_candidate_id BIGINT REFERENCES skill_avatar_candidate(id);
ALTER TABLE review_task ADD COLUMN avatar_removal BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_task ADD COLUMN avatar_removal_active_id BIGINT;
ALTER TABLE review_task ADD COLUMN avatar_removal_pending_id BIGINT;
ALTER TABLE skill_avatar_candidate ADD COLUMN review_bound BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_review_avatar_candidate ON review_task(avatar_candidate_id);
-- Retain existing pending image applications when upgrading to the shared queue.
INSERT INTO review_task (skill_id, namespace_id, subject_type, subject_id, subject_version,
    status, version, submitted_by, submitted_at, avatar_candidate_id)
SELECT s.id, s.namespace_id, 'SKILL_METADATA', s.id, 'avatar:' || c.id,
    'PENDING', 1, c.submitted_by, c.created_at, c.id
FROM skill_avatar_candidate c JOIN skill s ON s.id = c.skill_id AND s.pending_avatar_id = c.id
WHERE c.status = 'PENDING' AND c.review_status IN ('PENDING', 'APPEAL_PENDING');
UPDATE skill_avatar_candidate SET review_bound = TRUE WHERE id IN
    (SELECT avatar_candidate_id FROM review_task WHERE avatar_candidate_id IS NOT NULL);

-- Cloud model versions are not supplied by the current APIs; historical evidence stays unknown.
ALTER TABLE skill_avatar_candidate ADD COLUMN machine_service_id VARCHAR(128);
ALTER TABLE skill_avatar_candidate ADD COLUMN machine_api_version VARCHAR(64);
ALTER TABLE skill_avatar_candidate ADD COLUMN machine_policy_revision VARCHAR(256);
ALTER TABLE skill_avatar_candidate ADD COLUMN machine_model_version VARCHAR(128);
