ALTER TABLE github_discovery_entry
    ADD COLUMN source_visibility VARCHAR(20)
        CHECK (source_visibility IN ('PUBLIC', 'NAMESPACE_ONLY'));

UPDATE github_discovery_entry SET source_visibility = 'PUBLIC'
WHERE entry_type = 'CURATED_REPOSITORY';

ALTER TABLE github_discovery_entry
    DROP CONSTRAINT ck_github_discovery_entry_shape,
    ADD CONSTRAINT ck_github_discovery_entry_shape CHECK
        ((entry_type = 'SEARCH_RULE' AND owner_login IS NULL AND repository_name IS NULL
            AND source_visibility IS NULL AND jsonb_typeof(criteria_json) = 'object')
         OR (entry_type = 'CURATED_OWNER' AND owner_login IS NOT NULL
            AND repository_name IS NULL AND source_visibility IS NULL
            AND criteria_json IS NULL)
         OR (entry_type = 'CURATED_REPOSITORY' AND owner_login IS NOT NULL
            AND repository_name IS NOT NULL AND source_visibility IS NOT NULL
            AND criteria_json IS NULL));
