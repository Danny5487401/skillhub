-- The catalog slug and actor are reserved. A collision must be handled explicitly by an
-- administrator; this migration must never adopt or overwrite an existing user's data.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM namespace WHERE slug = 'github') THEN
        RAISE EXCEPTION 'Cannot create @github catalog: namespace slug github already exists';
    END IF;
    IF EXISTS (SELECT 1 FROM user_account WHERE id = 'github-sync-publisher') THEN
        RAISE EXCEPTION 'Cannot create GitHub catalog actor: user id github-sync-publisher already exists';
    END IF;
END $$;

INSERT INTO user_account (id, display_name, status, system_account)
VALUES ('github-sync-publisher', 'GitHub Skill Sync', 'ACTIVE', TRUE);

INSERT INTO namespace (slug, display_name, type, description, status, created_by)
VALUES ('github', 'GitHub Skills', 'CATALOG', 'Public GitHub skill catalog', 'ACTIVE', 'github-sync-publisher');

INSERT INTO namespace_member (namespace_id, user_id, role)
SELECT id, 'github-sync-publisher', 'OWNER'
FROM namespace
WHERE slug = 'github';
