-- Land claims, copied from the world (the mod keeps them in nicecontrolcenter-claims.json) so a website
-- can read them. The panel writes these tables; nothing else should.
CREATE TABLE claims (
    id          UUID PRIMARY KEY,
    parent_id   UUID,                       -- the claim around a subclaim
    owner_uuid  UUID,                       -- NULL: a server claim
    owner_name  TEXT,
    dimension   TEXT NOT NULL,
    min_x       INTEGER NOT NULL,
    min_z       INTEGER NOT NULL,
    max_x       INTEGER NOT NULL,           -- inclusive block coordinates
    max_z       INTEGER NOT NULL,
    name        TEXT NOT NULL DEFAULT '',
    color       TEXT NOT NULL,              -- #RRGGBB
    flags       JSONB NOT NULL,             -- what non-members may do: access, containers, pvp, explosions, fireSpread, mobGriefing
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    rev         BIGINT NOT NULL
);
CREATE INDEX claims_owner ON claims (owner_uuid);
CREATE INDEX claims_parent ON claims (parent_id);

CREATE TABLE claim_members (
    claim_id    UUID NOT NULL REFERENCES claims (id) ON DELETE CASCADE,
    player_uuid UUID NOT NULL,
    player_name TEXT,
    role        TEXT NOT NULL,              -- ACCESS, CONTAINERS, BUILD, MANAGE
    PRIMARY KEY (claim_id, player_uuid)
);

CREATE TABLE claim_players (
    player_uuid  UUID PRIMARY KEY,
    name         TEXT,
    accrued      INTEGER NOT NULL DEFAULT 0, -- earned by playing (and the starting blocks)
    bonus        INTEGER NOT NULL DEFAULT 0, -- given by admins
    bought       INTEGER NOT NULL DEFAULT 0, -- bought in the claim shop
    play_minutes BIGINT NOT NULL DEFAULT 0,
    last_seen    TIMESTAMPTZ
);

CREATE TABLE claim_sync (
    id        INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    world_id  TEXT,
    rev       BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ
);

-- Claim blocks per player: what they have, use and can still spend.
CREATE VIEW claim_player_summary AS
SELECT p.player_uuid, p.name, p.accrued, p.bonus, p.bought, p.accrued + p.bonus + p.bought AS total,
       COALESCE(u.used, 0) AS used, p.accrued + p.bonus + p.bought - COALESCE(u.used, 0) AS available,
       COALESCE(u.claims, 0) AS claims, p.play_minutes, p.last_seen
FROM claim_players p
LEFT JOIN (SELECT owner_uuid, SUM((max_x - min_x + 1)::BIGINT * (max_z - min_z + 1)) AS used, COUNT(*) AS claims
           FROM claims WHERE parent_id IS NULL GROUP BY owner_uuid) u ON u.owner_uuid = p.player_uuid;

INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, p FROM roles r, unnest(ARRAY['claims.view', 'claims.manage']) p WHERE r.name = 'Moderator'
    ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
    SELECT r.id, 'claims.view' FROM roles r WHERE r.name = 'Viewer'
    ON CONFLICT DO NOTHING;
