-- Track what a complete repository scan observed without changing Skill publication state.
ALTER TABLE github_source_skill_binding
    ADD COLUMN upstream_presence VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN presence_commit_sha VARCHAR(40),
    ADD COLUMN missing_since TIMESTAMPTZ,
    ADD CONSTRAINT ck_github_skill_upstream_presence
        CHECK (upstream_presence IN ('UNKNOWN', 'PRESENT', 'MISSING')),
    ADD CONSTRAINT ck_github_skill_missing_since
        CHECK ((upstream_presence = 'MISSING') = (missing_since IS NOT NULL));

CREATE INDEX idx_github_skill_missing_source
    ON github_source_skill_binding (source_id, missing_since)
    WHERE upstream_presence = 'MISSING';
