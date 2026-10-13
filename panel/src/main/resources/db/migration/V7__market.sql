-- The player market (/market), copied from the world (nicecontrolcenter-market.json) for a website to read.
CREATE TABLE market_listings (
    id           UUID PRIMARY KEY,
    seller_uuid  UUID NOT NULL,
    seller_name  TEXT,
    item_id      TEXT NOT NULL,
    item_name    TEXT NOT NULL,
    item         JSONB NOT NULL,            -- the stack with all components, as the game writes it
    lore         JSONB NOT NULL DEFAULT '[]',
    enchantments JSONB NOT NULL DEFAULT '[]',
    amount       INTEGER NOT NULL,          -- items left
    price        INTEGER NOT NULL,          -- per item, in the server's currency
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX market_listings_item ON market_listings (item_id);

CREATE TABLE market_sales (
    listing_id  UUID NOT NULL,
    item_id     TEXT NOT NULL,
    item_name   TEXT,
    seller_uuid UUID,
    seller_name TEXT,
    buyer_uuid  UUID,
    buyer_name  TEXT,
    amount      INTEGER NOT NULL,
    total       INTEGER NOT NULL,
    sold_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listing_id, buyer_uuid, sold_at)
);

CREATE TABLE market_sync (
    id        INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    world_id  TEXT,
    rev       BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ
);
