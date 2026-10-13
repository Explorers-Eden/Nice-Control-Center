-- Item icons for a website (the key the panel serves at /info/items/<icon>.png) and the server's currency.
ALTER TABLE market_listings ADD COLUMN icon TEXT;
ALTER TABLE market_sync ADD COLUMN currency TEXT;
ALTER TABLE market_sync ADD COLUMN currency_icon TEXT;
