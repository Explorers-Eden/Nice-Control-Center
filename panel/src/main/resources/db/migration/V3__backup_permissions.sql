-- Backups: Moderators may see and make them; restoring and deleting stays with Admin unless granted.
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, p FROM roles r, unnest(ARRAY['backup.view', 'backup.create']) p WHERE r.name = 'Moderator'
    ON CONFLICT DO NOTHING;
