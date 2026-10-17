-- Player listings can be priced per lot ("5 per 16"): price is for this many items, bought that many at a time.
ALTER TABLE market_listings ADD COLUMN per INTEGER NOT NULL DEFAULT 1;
