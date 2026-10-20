CREATE TABLE github_sync_schedule_policy (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    repository_hours INTEGER CHECK (repository_hours BETWEEN 1 AND 720),
    curated_owner_hours INTEGER NOT NULL DEFAULT 24 CHECK (curated_owner_hours BETWEEN 1 AND 720),
    search_rule_hours INTEGER NOT NULL DEFAULT 72 CHECK (search_rule_hours BETWEEN 1 AND 720),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- NULL keeps the existing deployment-level repository interval until an admin saves this policy.
INSERT INTO github_sync_schedule_policy (id) VALUES (TRUE);
