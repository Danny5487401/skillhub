ALTER TABLE github_discovery_candidate_origin
    DROP CONSTRAINT github_discovery_candidate_origin_origin_type_check,
    ADD CONSTRAINT github_discovery_candidate_origin_origin_type_check
        CHECK (origin_type IN ('SEARCH_RULE', 'CURATED_OWNER', 'OFFICIAL_DISCOVERY'));
ALTER TABLE github_source_origin
    DROP CONSTRAINT ck_github_source_origin_type,
    ADD CONSTRAINT ck_github_source_origin_type
        CHECK (origin_type IN ('SEARCH_RULE', 'CURATED_OWNER', 'CURATED_REPOSITORY', 'TEAM_CONFIG', 'OFFICIAL_DISCOVERY'));
ALTER TABLE github_sync_task ADD COLUMN official_pause_reason VARCHAR(64);
ALTER TABLE github_deep_discovery_query ADD COLUMN last_full_recheck_at TIMESTAMPTZ,
    ADD COLUMN gap_reason VARCHAR(64);
ALTER TABLE github_discovery_batch ADD COLUMN official_metadata JSONB;

-- Current independent entry state, rather than a historical origin alone, grants eligibility.
CREATE VIEW github_candidate_discovery_eligibility AS
SELECT candidate.id AS candidate_id, candidate.namespace_id, candidate.repository_id,
       source.id AS source_id, source.status AS source_status,
       EXISTS (SELECT 1 FROM github_discovery_candidate_origin origin
               WHERE origin.candidate_id = candidate.id AND origin.origin_type = 'OFFICIAL_DISCOVERY') AS has_official,
       EXISTS (
           SELECT 1 FROM github_discovery_candidate_origin origin
           JOIN github_discovery_entry entry ON entry.namespace_id = candidate.namespace_id AND entry.enabled
             AND ((origin.origin_type = 'SEARCH_RULE' AND entry.entry_type = 'SEARCH_RULE'
                   AND origin.origin_key = entry.id::text)
               OR (origin.origin_type = 'CURATED_OWNER' AND entry.entry_type = 'CURATED_OWNER'
                   AND lower(origin.origin_key) = lower(entry.owner_login)))
           WHERE origin.candidate_id = candidate.id) AS has_independent
FROM github_discovery_candidate candidate
LEFT JOIN github_source_binding source
    ON source.namespace_id = candidate.namespace_id AND source.repository_id = candidate.repository_id;
