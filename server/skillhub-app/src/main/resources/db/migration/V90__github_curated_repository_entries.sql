ALTER TABLE github_discovery_entry
    ADD COLUMN repository_name VARCHAR(100),
    ADD COLUMN repository_id BIGINT CHECK (repository_id > 0);

ALTER TABLE github_discovery_entry
    DROP CONSTRAINT ck_github_discovery_entry_type,
    ADD CONSTRAINT ck_github_discovery_entry_type CHECK
        (entry_type IN ('SEARCH_RULE', 'CURATED_OWNER', 'CURATED_REPOSITORY')),
    DROP CONSTRAINT ck_github_discovery_entry_shape,
    ADD CONSTRAINT ck_github_discovery_entry_shape CHECK
        ((entry_type = 'SEARCH_RULE' AND owner_login IS NULL AND repository_name IS NULL
            AND jsonb_typeof(criteria_json) = 'object')
         OR (entry_type = 'CURATED_OWNER' AND owner_login IS NOT NULL
            AND repository_name IS NULL AND criteria_json IS NULL)
         OR (entry_type = 'CURATED_REPOSITORY' AND owner_login IS NOT NULL
            AND repository_name IS NOT NULL AND criteria_json IS NULL));

CREATE UNIQUE INDEX uq_github_discovery_curated_repository
    ON github_discovery_entry (namespace_id, lower(owner_login), lower(repository_name))
    WHERE entry_type = 'CURATED_REPOSITORY';

ALTER TABLE github_discovery_batch
    ADD COLUMN curated_repository_id BIGINT CHECK (curated_repository_id > 0);

CREATE UNIQUE INDEX uq_github_discovery_batch_curated_repository
    ON github_discovery_batch (task_id)
    WHERE curated_repository_id IS NOT NULL;
