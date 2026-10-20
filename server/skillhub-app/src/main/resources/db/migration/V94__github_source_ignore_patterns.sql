ALTER TABLE github_source_binding
    ADD COLUMN ignore_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_github_source_ignore_patterns_array
        CHECK (jsonb_typeof(ignore_patterns) = 'array');
