-- One sequential tree per query. Completed trees are removed when their next root is scheduled;
-- long-lived provenance and confirmed coverage remain on candidate origins and query progress.
CREATE TABLE github_official_discovery_shard (
    id UUID PRIMARY KEY,
    query_id UUID NOT NULL REFERENCES github_deep_discovery_query(id),
    root_id UUID NOT NULL REFERENCES github_official_discovery_shard(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES github_official_discovery_shard(id) ON DELETE CASCADE,
    depth INTEGER NOT NULL CHECK (depth BETWEEN 0 AND 32),
    range_since TIMESTAMPTZ NOT NULL,
    range_until TIMESTAMPTZ NOT NULL,
    snapshot_json JSONB NOT NULL CHECK (jsonb_typeof(snapshot_json) = 'object'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'SPLIT', 'COMPLETE', 'GAP')),
    task_id UUID UNIQUE REFERENCES github_sync_task(id) ON DELETE SET NULL,
    retry_at TIMESTAMPTZ,
    gap_reason VARCHAR(64),
    gap_checkpoint VARCHAR(4096),
    last_lease_generation BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK (range_since < range_until),
    CHECK (date_trunc('second', range_since) = range_since AND date_trunc('second', range_until) = range_until),
    CHECK ((parent_id IS NULL AND root_id = id AND depth = 0) OR (parent_id IS NOT NULL AND root_id <> id AND depth > 0))
);
CREATE UNIQUE INDEX uq_github_official_shard_open_root ON github_official_discovery_shard (query_id)
    WHERE parent_id IS NULL AND state <> 'COMPLETE';
CREATE INDEX idx_github_official_shard_pending ON github_official_discovery_shard (query_id, range_since, depth)
    WHERE state = 'PENDING';
CREATE INDEX idx_github_official_shard_root ON github_official_discovery_shard (root_id);
