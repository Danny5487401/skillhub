CREATE TABLE github_sync_worker_presence (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_seen_at TIMESTAMPTZ NOT NULL
);
