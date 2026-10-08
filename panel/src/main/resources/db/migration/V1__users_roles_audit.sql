-- Panel accounts, roles with permission strings, login sessions and the audit log.

CREATE TABLE roles (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    -- Built-in roles can't be renamed or deleted; Admin's permissions can't be changed either.
    builtin     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE role_permissions (
    role_id     INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission  TEXT NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE users (
    id              SERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    disabled        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login      TIMESTAMPTZ
);
CREATE UNIQUE INDEX users_name_lower ON users (lower(name));

CREATE TABLE user_roles (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sessions (
    -- SHA-256 of the cookie value, so a database dump can't be used to log in.
    token_hash  TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    ip          TEXT,
    user_agent  TEXT
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_name   TEXT,
    action      TEXT NOT NULL,
    detail      TEXT,
    ip          TEXT
);
CREATE INDEX audit_log_time ON audit_log (time DESC);

INSERT INTO roles (name, builtin) VALUES ('Admin', TRUE), ('Moderator', TRUE), ('Viewer', TRUE);
INSERT INTO role_permissions (role_id, permission)
    SELECT id, '*' FROM roles WHERE name = 'Admin';
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, p FROM roles r, unnest(ARRAY['server.view', 'server.power', 'console.read', 'console.write', 'audit.view']) p WHERE r.name = 'Moderator';
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, p FROM roles r, unnest(ARRAY['server.view', 'console.read']) p WHERE r.name = 'Viewer';
