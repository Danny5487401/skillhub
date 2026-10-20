-- Live eligibility: registered source policy wins, then current independent entries, then official controls.
CREATE OR REPLACE VIEW github_candidate_discovery_eligibility AS
WITH origin_state AS (
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
    ON source.namespace_id = candidate.namespace_id AND source.repository_id = candidate.repository_id
), controls AS (
    SELECT origin_state.*, COALESCE(config.enabled, FALSE) AS configuration_enabled,
           EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(config.exclusions_json, '[]'::jsonb)) exclusion
                   JOIN github_discovery_candidate candidate ON candidate.id = origin_state.candidate_id
                   WHERE (exclusion ->> 'kind' = 'OWNER' AND exclusion ->> 'value' = lower(candidate.owner_login))
                      OR (exclusion ->> 'kind' = 'REPOSITORY'
                          AND exclusion ->> 'value' = lower(candidate.owner_login || '/' || candidate.repository_name))) AS excluded
    FROM origin_state LEFT JOIN github_deep_discovery_config config ON config.namespace_id = origin_state.namespace_id
)
SELECT candidate_id, namespace_id, repository_id, source_id, source_status, has_official, has_independent,
       has_official AND NOT has_independent AND source_id IS NULL AS official_only,
       configuration_enabled, excluded,
       CASE WHEN source_id IS NOT NULL THEN source_status <> 'PAUSED'
            WHEN NOT has_official OR has_independent THEN TRUE
            ELSE configuration_enabled AND NOT excluded END AS acquisition_allowed
FROM controls;
