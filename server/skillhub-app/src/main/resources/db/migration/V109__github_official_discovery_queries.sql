-- Monotonic activation prevents mixed Java versions from rolling the catalog backward.
ALTER TABLE github_deep_discovery_config
    ADD COLUMN strategy_generation BIGINT NOT NULL DEFAULT 0 CHECK (strategy_generation >= 0);

CREATE TABLE github_deep_discovery_query (
    id UUID PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    query_key VARCHAR(128) NOT NULL,
    query_version BIGINT NOT NULL CHECK (query_version > 0),
    strategy_version VARCHAR(128) NOT NULL,
    definition_json JSONB NOT NULL CHECK (jsonb_typeof(definition_json) = 'object'),
    covered_until TIMESTAMPTZ,
    checkpoint VARCHAR(4096),
    next_due_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_task_id UUID REFERENCES github_sync_task(id),
    last_progress_at TIMESTAMPTZ,
    wait_reason VARCHAR(64),
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_deep_discovery_query UNIQUE (namespace_id, query_key, query_version),
    CONSTRAINT uq_github_deep_discovery_query_namespace UNIQUE (id, namespace_id)
);

CREATE INDEX idx_github_deep_discovery_query_due
    ON github_deep_discovery_query (next_due_at, id)
    WHERE retired_at IS NULL AND active_task_id IS NULL;

ALTER TABLE github_sync_task
    ADD COLUMN required_capabilities TEXT[] NOT NULL DEFAULT '{}'::text[],
    ADD COLUMN official_query_id UUID,
    ADD COLUMN last_progress_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_github_sync_task_capability_count
        CHECK (cardinality(required_capabilities) <= 32
            AND array_position(required_capabilities, NULL) IS NULL),
    ADD CONSTRAINT ck_github_sync_task_official_capabilities
        CHECK (official_query_id IS NULL OR (kind = 'DISCOVERY' AND required_capabilities @>
            ARRAY['official-discovery/v1', 'official-checkpoint/v1', 'official-pause/v1']::text[])),
    ADD CONSTRAINT fk_github_sync_task_official_query
        FOREIGN KEY (official_query_id, namespace_id)
        REFERENCES github_deep_discovery_query(id, namespace_id);

CREATE UNIQUE INDEX uq_github_official_query_open_task
    ON github_sync_task (official_query_id)
    WHERE official_query_id IS NOT NULL AND status IN ('QUEUED', 'LEASED', 'RETRY_WAIT');
