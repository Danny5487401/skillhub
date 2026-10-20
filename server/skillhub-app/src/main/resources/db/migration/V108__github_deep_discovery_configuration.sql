CREATE TABLE github_deep_discovery_config (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id = TRUE),
    namespace_id BIGINT NOT NULL UNIQUE REFERENCES namespace(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    budget_profile VARCHAR(16) NOT NULL DEFAULT 'BALANCED'
        CHECK (budget_profile IN ('ECONOMY', 'BALANCED', 'ACCELERATED')),
    config_version BIGINT NOT NULL DEFAULT 1 CHECK (config_version > 0),
    exclusions_json JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(exclusions_json) = 'array' AND jsonb_array_length(exclusions_json) <= 1000),
    strategy_version VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128)
);

-- No strategy is installed by this configuration-only migration.
INSERT INTO github_deep_discovery_config (namespace_id)
VALUES ((SELECT id FROM namespace WHERE slug = 'github' AND type = 'CATALOG'));
