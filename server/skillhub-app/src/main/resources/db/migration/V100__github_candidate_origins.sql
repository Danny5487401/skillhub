-- Candidate provenance must outlive the discovery task detail retention window.
CREATE TABLE github_discovery_candidate_origin (
    candidate_id BIGINT NOT NULL REFERENCES github_discovery_candidate(id) ON DELETE CASCADE,
    origin_type VARCHAR(32) NOT NULL CHECK (origin_type IN ('SEARCH_RULE', 'CURATED_OWNER')),
    origin_key VARCHAR(256) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (candidate_id, origin_type, origin_key)
);

INSERT INTO github_discovery_candidate_origin
    (candidate_id, origin_type, origin_key, first_seen_at, last_seen_at)
SELECT hit.candidate_id,
       task.snapshot_json ->> 'entryType',
       CASE WHEN task.snapshot_json ->> 'entryType' = 'CURATED_OWNER'
            THEN task.snapshot_json ->> 'owner'
            ELSE task.snapshot_json ->> 'ruleId' END,
       MIN(hit.created_at), MAX(hit.created_at)
FROM github_discovery_task_hit hit
JOIN github_sync_task task ON task.id = hit.task_id
WHERE task.snapshot_json ->> 'entryType' IN ('SEARCH_RULE', 'CURATED_OWNER')
  AND CASE WHEN task.snapshot_json ->> 'entryType' = 'CURATED_OWNER'
           THEN task.snapshot_json ->> 'owner'
           ELSE task.snapshot_json ->> 'ruleId' END IS NOT NULL
GROUP BY hit.candidate_id, task.snapshot_json ->> 'entryType',
         CASE WHEN task.snapshot_json ->> 'entryType' = 'CURATED_OWNER'
              THEN task.snapshot_json ->> 'owner'
              ELSE task.snapshot_json ->> 'ruleId' END;
