ALTER TABLE skill ADD COLUMN active_avatar_id BIGINT;
ALTER TABLE skill ADD COLUMN active_avatar_audience VARCHAR(32);
ALTER TABLE skill ADD COLUMN pending_avatar_id BIGINT;

CREATE TABLE skill_avatar_candidate (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    submitted_by VARCHAR(128) NOT NULL,
    object_key VARCHAR(512),
    content_sha256 VARCHAR(64) NOT NULL,
    audience VARCHAR(32) NOT NULL,
    machine_enabled BOOLEAN NOT NULL,
    manual_enabled BOOLEAN NOT NULL,
    machine_provider VARCHAR(32),
    scope_review_version_id BIGINT,
    scope_review BOOLEAN NOT NULL DEFAULT FALSE,
    machine_attempt_id VARCHAR(36),
    machine_lease_until TIMESTAMP,
    machine_next_retry_at TIMESTAMP,
    machine_attempt_count INTEGER NOT NULL DEFAULT 0,
    machine_status VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    review_reason TEXT,
    reviewed_by VARCHAR(128),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    discard_at TIMESTAMP
);

CREATE INDEX idx_avatar_candidate_skill ON skill_avatar_candidate(skill_id, created_at DESC);
CREATE INDEX idx_avatar_candidate_review ON skill_avatar_candidate(status, review_status, created_at);
CREATE INDEX idx_avatar_candidate_discard ON skill_avatar_candidate(discard_at) WHERE object_key IS NOT NULL;

INSERT INTO system_setting(setting_key, value_json)
VALUES ('skill.avatar-review', '{"machineEnabled":false,"manualEnabled":true}'::jsonb);
