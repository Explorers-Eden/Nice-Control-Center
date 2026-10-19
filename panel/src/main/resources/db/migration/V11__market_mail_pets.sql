-- For the website: what waits in each player's market mailbox, and the pets each player owns.
CREATE TABLE market_mail (
    player_uuid UUID PRIMARY KEY,
    player_name TEXT,
    currency    INTEGER NOT NULL DEFAULT 0,
    -- [{item_id, item_name, amount, icon}]
    parcels     JSONB NOT NULL DEFAULT '[]'
);

CREATE TABLE pets_owned (
    player_uuid UUID NOT NULL,
    pet_id      TEXT NOT NULL,
    pet_name    TEXT,
    -- Skin hash of the head (textures.minecraft.net/texture/<head>).
    head        TEXT,
    since       TIMESTAMPTZ,
    active      BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (player_uuid, pet_id)
);
