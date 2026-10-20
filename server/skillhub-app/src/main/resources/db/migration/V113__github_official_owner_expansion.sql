-- Stable GitHub account identity is distinct from SkillHub's own owner identifiers.
ALTER TABLE github_discovery_candidate ADD COLUMN github_owner_id BIGINT CHECK (github_owner_id > 0);
ALTER TABLE github_source_binding ADD COLUMN github_owner_id BIGINT CHECK (github_owner_id > 0);

-- Validation evidence survives ingestion/task history cleanup and later independent discovery.
CREATE TABLE github_official_validated_seed (
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    github_owner_id BIGINT NOT NULL CHECK (github_owner_id > 0),
    owner_login VARCHAR(39) NOT NULL,
    ingestion_id UUID NOT NULL,
    validated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (namespace_id, repository_id, github_owner_id)
);
ALTER TABLE github_deep_discovery_query
    ADD COLUMN query_source VARCHAR(16) NOT NULL DEFAULT 'CATALOG' CHECK (query_source IN ('CATALOG', 'OWNER')),
    ADD COLUMN github_owner_id BIGINT CHECK (github_owner_id > 0),
    ADD COLUMN owner_login VARCHAR(39),
    ADD COLUMN seed_repository_id BIGINT,
    ADD CONSTRAINT ck_github_official_owner_query CHECK (
        query_source = 'CATALOG' OR (github_owner_id IS NOT NULL AND owner_login IS NOT NULL AND seed_repository_id IS NOT NULL AND seed_repository_id > 0));
CREATE UNIQUE INDEX uq_github_official_owner_query ON github_deep_discovery_query(namespace_id, github_owner_id)
    WHERE query_source = 'OWNER';

-- Refreshable account coordinates never change the stable account identity of an owner query.
CREATE VIEW github_official_owner_eligibility AS
SELECT query.id AS query_id,
    EXISTS (SELECT 1 FROM jsonb_array_elements(config.exclusions_json) exclusion
        WHERE exclusion ->> 'kind' = 'OWNER'
          AND lower(exclusion ->> 'value') = lower(query.owner_login)) AS excluded
FROM github_deep_discovery_query query
JOIN github_deep_discovery_config config ON config.namespace_id = query.namespace_id
WHERE query.query_source = 'OWNER';
