-- Content revisions are local to a Skill. A retry of identical content keeps its
-- revision; returning to an earlier digest after a different digest gets a new one.
ALTER TABLE skill_version ADD COLUMN github_sync_revision INTEGER;
ALTER TABLE github_source_skill_binding
    ADD COLUMN latest_revision INTEGER NOT NULL DEFAULT 0 CHECK (latest_revision >= 0);

WITH ordered AS (
    SELECT version.id, version.skill_id, ingestion.content_sha256,
           LAG(ingestion.content_sha256) OVER (
               PARTITION BY version.skill_id ORDER BY version.id
           ) AS previous_content_sha256
    FROM skill_version version
    JOIN github_sync_package_ingestion ingestion
      ON ingestion.id = version.github_sync_ingestion_id
), numbered AS (
    SELECT id, SUM(CASE WHEN content_sha256 IS DISTINCT FROM previous_content_sha256
                        THEN 1 ELSE 0 END) OVER (
               PARTITION BY skill_id ORDER BY id
           ) AS revision
    FROM ordered
)
UPDATE skill_version version
SET github_sync_revision = numbered.revision
FROM numbered WHERE version.id = numbered.id;

UPDATE github_source_skill_binding binding
SET latest_revision = COALESCE((
    SELECT MAX(version.github_sync_revision)
    FROM skill_version version WHERE version.skill_id = binding.skill_id
), 0);

ALTER TABLE skill_version ADD CONSTRAINT ck_skill_version_github_sync_revision
    CHECK (github_sync_revision IS NULL
        OR (github_sync_ingestion_id IS NOT NULL AND github_sync_revision > 0));
