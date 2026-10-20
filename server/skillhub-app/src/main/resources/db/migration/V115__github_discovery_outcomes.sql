-- Long-lived business facts deliberately have no foreign key to disposable task history.
CREATE TABLE github_discovery_metrics_epoch (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    available_since TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO github_discovery_metrics_epoch (id) VALUES (TRUE);
CREATE TABLE github_discovery_outcome_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN
      ('CANDIDATE_CREATED','FIRST_VALID','NEW_SKILL','UPDATED_VERSION')),
    subject_key BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ,
    official_at_event BOOLEAN NOT NULL,
    first_occurrence_known BOOLEAN NOT NULL DEFAULT TRUE,
    query_ids UUID[] NOT NULL DEFAULT '{}',
    UNIQUE (namespace_id, event_type, subject_key)
);
-- Historical markers prevent a later official hit from becoming a new discovery.
-- NULL timestamps/false attribution explicitly carry no historical metric claim.
INSERT INTO github_discovery_outcome_event
    (namespace_id, repository_id, event_type, subject_key, official_at_event)
SELECT namespace_id, repository_id, 'CANDIDATE_CREATED', repository_id, FALSE
FROM github_discovery_candidate;
-- Existing validated packages and published bindings are factual prior validation evidence.
INSERT INTO github_discovery_outcome_event
    (namespace_id, repository_id, event_type, subject_key, official_at_event)
SELECT namespace_id, repository_id, 'FIRST_VALID', repository_id, FALSE FROM (
    SELECT namespace_id, repository_id FROM github_sync_package_ingestion WHERE validated_at IS NOT NULL
    UNION SELECT namespace_id, repository_id FROM github_source_skill_binding
    UNION SELECT namespace_id, repository_id FROM github_official_validated_seed
) prior_validation ON CONFLICT DO NOTHING;
-- Missing old evidence cannot prove whether the next validation is the first-ever one.
CREATE TABLE github_discovery_legacy_unknown_validation (
    namespace_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    PRIMARY KEY (namespace_id, repository_id)
);
INSERT INTO github_discovery_legacy_unknown_validation
SELECT c.namespace_id,c.repository_id FROM (
    SELECT namespace_id,repository_id FROM github_discovery_candidate
    UNION SELECT namespace_id,repository_id FROM github_source_binding
) c
WHERE NOT EXISTS(SELECT 1 FROM github_discovery_outcome_event e
    WHERE e.namespace_id=c.namespace_id AND e.repository_id=c.repository_id AND e.event_type='FIRST_VALID');
CREATE INDEX idx_discovery_outcome_recent ON github_discovery_outcome_event
    (namespace_id, occurred_at, event_type) WHERE official_at_event;
ALTER TABLE github_discovery_candidate_origin
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN recent_shard_id UUID,
    ADD COLUMN recent_run_id UUID,
    ADD COLUMN recent_seed_repository_id BIGINT,
    ADD COLUMN latest_strategy_version VARCHAR(128);
CREATE UNIQUE INDEX idx_discovery_origin_id ON github_discovery_candidate_origin(id);
CREATE INDEX idx_discovery_origin_recent ON github_discovery_candidate_origin
    (candidate_id, last_seen_at DESC, id DESC) WHERE origin_type='OFFICIAL_DISCOVERY';
CREATE INDEX idx_discovery_origin_query ON github_discovery_candidate_origin
    (origin_key, candidate_id) WHERE origin_type='OFFICIAL_DISCOVERY';
CREATE INDEX idx_discovery_candidate_all_recent ON github_discovery_candidate
    (namespace_id, first_seen_at DESC, id DESC);
CREATE INDEX idx_github_task_repository_recent ON github_sync_task
    (namespace_id, (snapshot_json->>'repositoryId'), created_at DESC, id DESC)
    WHERE kind='REPOSITORY_SYNC';
