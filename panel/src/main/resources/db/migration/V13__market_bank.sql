-- The market bank (mod 1.31.0): each player's balance, and command listings bought on the website waiting to be claimed in game.
ALTER TABLE market_mail ADD COLUMN balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE market_mail ADD COLUMN orders JSONB NOT NULL DEFAULT '[]';
