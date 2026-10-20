ALTER TABLE github_sync_admission_policy
    ADD COLUMN candidate_pending_limit INTEGER NOT NULL DEFAULT 1000
        CHECK (candidate_pending_limit BETWEEN 1 AND 100000),
    ADD COLUMN candidate_per_lease_limit INTEGER NOT NULL DEFAULT 100
        CHECK (candidate_per_lease_limit BETWEEN 1 AND 10000);
