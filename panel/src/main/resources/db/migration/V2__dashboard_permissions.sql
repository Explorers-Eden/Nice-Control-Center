-- The mod's dashboard inside the panel: sensible defaults for the built-in roles.
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, p FROM roles r, unnest(ARRAY['dashboard.view', 'players.manage']) p WHERE r.name = 'Moderator'
    ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, 'dashboard.view' FROM roles r WHERE r.name = 'Viewer'
    ON CONFLICT DO NOTHING;
