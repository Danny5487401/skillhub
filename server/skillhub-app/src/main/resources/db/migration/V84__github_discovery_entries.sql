CREATE TABLE github_discovery_entry (
    id UUID PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    entry_type VARCHAR(24) NOT NULL,
    title VARCHAR(128) NOT NULL,
    owner_login VARCHAR(128),
    criteria_json JSONB,
    rule_version BIGINT NOT NULL DEFAULT 1 CHECK (rule_version > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    target_interval_seconds INTEGER NOT NULL CHECK
        (target_interval_seconds BETWEEN 3600 AND 2592000),
    next_due_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_github_discovery_entry_type CHECK
        (entry_type IN ('SEARCH_RULE', 'CURATED_OWNER')),
    CONSTRAINT ck_github_discovery_entry_shape CHECK
        ((entry_type = 'SEARCH_RULE' AND owner_login IS NULL
            AND jsonb_typeof(criteria_json) = 'object')
         OR (entry_type = 'CURATED_OWNER' AND owner_login IS NOT NULL
            AND criteria_json IS NULL))
);

CREATE UNIQUE INDEX uq_github_discovery_rule_title
    ON github_discovery_entry (namespace_id, lower(title))
    WHERE entry_type = 'SEARCH_RULE';

CREATE UNIQUE INDEX uq_github_discovery_curated_owner
    ON github_discovery_entry (namespace_id, lower(owner_login))
    WHERE entry_type = 'CURATED_OWNER';

CREATE INDEX idx_github_discovery_entry_due
    ON github_discovery_entry (next_due_at, id)
    WHERE enabled = TRUE;

-- Initial search terms are only an editable starting point. Upgrade must not trigger discovery.
INSERT INTO github_discovery_entry
    (id, namespace_id, entry_type, title, criteria_json, enabled, target_interval_seconds)
SELECT '82c4e916-42f1-4a7b-9c30-116d5faac201'::uuid, id,
       'SEARCH_RULE', '公开 Agent Skill 仓库',
       '{"selectors":[{"kind":"KEYWORD","value":"agent skills","priority":"CORE"},
                      {"kind":"TOPIC","value":"agent-skills","priority":"EXTENSION"}]}'::jsonb,
       FALSE, 259200
FROM namespace WHERE slug = 'github' AND type = 'CATALOG';
