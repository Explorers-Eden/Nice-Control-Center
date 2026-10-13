-- Minecraft ↔ Discord account links from the panel's Discord bridge (the panel keeps them in
-- discord-links.json and copies them here when they change), for a website to read.
CREATE TABLE discord_links (
    player_uuid  UUID PRIMARY KEY,
    player_name  TEXT,
    discord_id   TEXT NOT NULL,
    discord_name TEXT,
    linked_at    TIMESTAMPTZ
);
CREATE INDEX discord_links_discord ON discord_links (discord_id);
