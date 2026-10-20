-- One deployment-wide pacing gate. No accumulated idle credit and no refund after a grant.
CREATE TABLE github_official_discovery_budget (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    next_lease_eligible_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO github_official_discovery_budget(id) VALUES(TRUE);
CREATE TABLE github_official_discovery_reservation (
    task_id UUID NOT NULL REFERENCES github_sync_task(id) ON DELETE CASCADE,
    lease_generation BIGINT NOT NULL,
    budget_profile VARCHAR(16) NOT NULL,
    reserved_requests INTEGER NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    next_lease_eligible_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(task_id, lease_generation)
);
ALTER TABLE github_deep_discovery_query ADD COLUMN last_successful_claim_at TIMESTAMPTZ;
CREATE INDEX idx_github_official_active_lease ON github_sync_task(lease_expires_at)
    WHERE kind='DISCOVERY' AND official_query_id IS NOT NULL AND status='LEASED';
