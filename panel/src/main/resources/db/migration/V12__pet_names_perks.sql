-- For the website: the name a player gave their pet, its bond level and its perks (mod 1.17.0).
ALTER TABLE pets_owned
    ADD COLUMN nickname   TEXT,
    ADD COLUMN name_color TEXT,
    ADD COLUMN bond_level INTEGER,
    -- [{label, description, level, unlocked}]; empty while perks are off.
    ADD COLUMN perks      JSONB NOT NULL DEFAULT '[]';
