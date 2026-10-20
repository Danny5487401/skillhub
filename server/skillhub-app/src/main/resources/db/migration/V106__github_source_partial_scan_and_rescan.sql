-- Distinguish a completely enumerated revision with terminal Skill failures
-- from a revision whose repository scan still needs to resume.
ALTER TABLE github_source_binding
    ADD COLUMN last_partial_commit VARCHAR(40),
    ADD COLUMN manual_rescan_requested_at TIMESTAMPTZ;
