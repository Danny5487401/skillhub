-- Legacy presence remains a contact signal only; capabilities belong to a process instance.
CREATE TABLE github_sync_worker_instance (
    worker_id VARCHAR(128) PRIMARY KEY,
    protocol_major INTEGER NOT NULL CHECK (protocol_major = 1),
    capabilities TEXT[] NOT NULL CHECK (cardinality(capabilities) <= 32),
    last_seen_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_github_sync_worker_instance_seen ON github_sync_worker_instance (last_seen_at, worker_id);
