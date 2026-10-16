-- The world's player files and command storage, copied by the panel when they change, so a website can
-- read everything from the database (no SFTP). The panel writes these tables; nothing else should.
CREATE TABLE smp_players (
    player_uuid   UUID PRIMARY KEY,
    player_name   TEXT,                       -- from usercache.json
    race          TEXT,                       -- Fabled Roots race tag (fabled_roots.<race>), lower case
    class         TEXT,                       -- Fabled Roots class tag, lower case
    tags          JSONB NOT NULL DEFAULT '[]', -- the player's scoreboard tags (Tags in players/data/<uuid>.dat)
    stats         JSONB,                      -- players/stats/<uuid>.json as it is
    advancements  JSONB,                      -- players/advancements/<uuid>.json as it is (DataVersion included)
    data_version  INTEGER,                    -- DataVersion of the player's .dat (the game version that saved it)
    saved_at      TIMESTAMPTZ,                -- when the game last saved this player
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX smp_players_name ON smp_players (lower(player_name));

-- Command storage per namespace: data/<name>/command_storage.dat (26.3+) or data/command_storage_<name>.dat (data pack storage, e.g. waypoint hubs, homes and graves), as JSON in
-- prismarine-nbt's "simplified" shape: compounds are objects, lists and arrays are arrays, numbers are
-- numbers, and longs are [high, low] pairs of ints.
CREATE TABLE smp_command_storage (
    name        TEXT PRIMARY KEY,
    data        JSONB NOT NULL,
    saved_at    TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE smp_sync (
    id          INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    level_name  TEXT,
    players     INTEGER NOT NULL DEFAULT 0,
    synced_at   TIMESTAMPTZ
);
