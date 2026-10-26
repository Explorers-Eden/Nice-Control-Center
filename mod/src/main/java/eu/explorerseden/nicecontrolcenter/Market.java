package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The player market (/market): players list items for the server's currency item (any amount, several
 * stacks), others buy part or all of a listing. The game holds listed items and earnings until they're
 * bought or collected; unsold items go back to the seller's mailbox when a listing expires.
 *
 * Settings in config/nicecontrolcenter-market.json (written by the panel), listings in the world folder
 * (nicecontrolcenter-market.json). Items are kept as JSON with all their components (custom names,
 * enchantments, custom models…), written and read by the mod. Every change bumps a revision so the panel
 * can copy it into its database. Minecraft-free.
 */
public final class Market {
	public static final String FILE = "nicecontrolcenter-market.json";
	public static final String DATA_FILE = "nicecontrolcenter-market.json";
	private static final int TOMBSTONES = 2000;
	private static final int SALES = 1000;

	private Market() {
	}

	public static final class Settings {
		public boolean enabled = false;
		/** What players pay with. */
		public String currency = "minecraft:diamond";
		/** Unsold items go back after this many days. */
		public int listingDays = 7;
		/** Listings per player at once. */
		public int maxListings = 10;
		/** Highest price a player can ask (for one lot of a listing). */
		public int maxPrice = 100_000;
		/** Items that can't be listed (ids). */
		public List<String> bannedItems = new ArrayList<>();
		/** Claim blocks the server sells (while claims are on), on top of Browse; none = not sold. */
		public List<ClaimOffer> claimBlocks = new ArrayList<>();
		/** Most claim blocks a player can buy in total; 0 = no limit. */
		public int claimBlocksMax = 0;
		/** The seller shown for the server shop's listings (in game, in the sales and on the info page). */
		public String serverName = "the server";
		/** Things the server sells by running console commands for the buyer, on top of Browse. */
		public List<CommandOffer> commandOffers = new ArrayList<>();

		public Settings fill() {
			if (currency == null || currency.isBlank()) currency = "minecraft:diamond";
			currency = id(currency);
			if (bannedItems == null) bannedItems = new ArrayList<>();
			bannedItems = bannedItems.stream().filter(b -> b != null && !b.isBlank()).map(Market::id).distinct().toList();
			serverName = serverName == null || serverName.isBlank() ? "the server" : serverName.strip();
			if (claimBlocks == null) claimBlocks = new ArrayList<>();
			claimBlocks = new ArrayList<>(claimBlocks.stream().filter(o -> o != null).toList());
			for (ClaimOffer o : claimBlocks) {
				o.icon = o.icon == null || o.icon.isBlank() ? "minecraft:grass_block" : id(o.icon);
				o.iconModel = o.iconModel == null || o.iconModel.isBlank() ? "" : id(o.iconModel);
				o.iconTexture = headTexture(o.icon, o.iconTexture);
				o.id = uuidOr(o.id);
			}
			if (commandOffers == null) commandOffers = new ArrayList<>();
			commandOffers = new ArrayList<>(commandOffers.stream().filter(o -> o != null).toList());
			for (CommandOffer o : commandOffers) {
				o.id = uuidOr(o.id);
				o.icon = o.icon == null || o.icon.isBlank() ? "minecraft:paper" : id(o.icon);
				o.iconModel = o.iconModel == null || o.iconModel.isBlank() ? "" : id(o.iconModel);
				o.iconTexture = headTexture(o.icon, o.iconTexture);
				o.name = o.name == null ? "" : o.name.strip();
				o.description = o.description == null ? "" : o.description.strip().replaceAll("\\s+", " ");
				if (o.commands == null) o.commands = new ArrayList<>();
				o.commands = o.commands.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.strip().replaceFirst("^/", "")).toList();
			}
			return this;
		}

		public CommandOffer commandOffer(String id) {
			for (CommandOffer o : commandOffers) if (o.id.equals(id)) return o;
			return null;
		}

		/** The claim block listing at this position, or null. */
		public ClaimOffer claimOffer(int i) {
			return i >= 0 && i < claimBlocks.size() ? claimBlocks.get(i) : null;
		}
	}

	/** A claim block listing: {@link #blocks} blocks for {@link #price} in the currency. */
	public static final class ClaimOffer {
		/** For the sales list. */
		public String id;
		public int blocks = 100;
		public int price = 1;
		/** The listing's icon (an item id); only how it looks. */
		public String icon = "minecraft:grass_block";
		/** An item model for the icon (like one from the server's resource pack); empty = the item's own. */
		public String iconModel = "";
		/** The head's skin when the icon is a player head (base64 "textures" value); empty = a plain head. */
		public String iconTexture = "";
		/** When the listing goes (epoch ms); 0 = always on sale. */
		public long saleEnds;

		/** Off: kept in the panel but not sold. */
		public boolean forSale = true;

		public boolean onSale() {
			return forSale && (saleEnds == 0 || System.currentTimeMillis() < saleEnds);
		}
	}

	/** Words in a listing's commands that are replaced for the buyer. */
	public static final List<String> COMMAND_VARIABLES = List.of("{player}", "{uuid}", "{x}", "{y}", "{z}", "{dimension}");

	/** A listing that runs console commands for the buyer (once per one bought), with {@link #COMMAND_VARIABLES} filled in. */
	public static final class CommandOffer {
		public String id;
		public String name = "";
		public String description = "";
		/** The listing's icon (an item id); only how it looks. */
		public String icon = "minecraft:paper";
		/** An item model for the icon (like one from the server's resource pack); empty = the item's own. */
		public String iconModel = "";
		/** The head's skin when the icon is a player head (base64 "textures" value); empty = a plain head. */
		public String iconTexture = "";
		public int price = 1;
		public List<String> commands = new ArrayList<>();
		/** When the listing goes (epoch ms); 0 = always on sale. */
		public long saleEnds;
		/** How many each player can buy in all; 0 = no limit. */
		public int perPlayer;

		/** Off: kept in the panel but not sold. */
		public boolean forSale = true;

		public boolean onSale() {
			return forSale && (saleEnds == 0 || System.currentTimeMillis() < saleEnds);
		}
	}

	/** The id if it's a UUID (sales are kept by listing UUID), else a new one. */
	private static String uuidOr(String id) {
		try {
			return UUID.fromString(id).toString();
		} catch (RuntimeException e) {
			return UUID.randomUUID().toString();
		}
	}

	/** A player head icon's skin in its stored form (raw when it isn't one, so validate() names it); empty for other icons. */
	private static String headTexture(String icon, String texture) {
		if (!HEAD.equals(icon) || texture == null || texture.isBlank()) return "";
		String t = Pets.texture(texture);
		return t != null ? t : texture.strip();
	}

	/** The icon that can have {@link ClaimOffer#iconTexture}. */
	public static final String HEAD = "minecraft:player_head";

	private static String id(String s) {
		String t = s.strip().toLowerCase(Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

	private static final String TEXTURE_HELP = "isn't a skin value (base64 from a head generator) or a textures.minecraft.net link.";

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (!s.currency.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "The currency isn't an item id (like minecraft:diamond).";
		if (s.listingDays < 1 || s.listingDays > 365) return "Listings last 1 to 365 days.";
		if (s.maxListings < 1 || s.maxListings > 200) return "Players can have 1 to 200 listings.";
		if (s.maxPrice < 1 || s.maxPrice > 10_000_000) return "The highest price is 1 to 10,000,000.";
		for (String b : s.bannedItems) if (!b.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + b + "\" isn't an item id.";
		if (s.claimBlocks.size() > 20) return "At most 20 claim block listings.";
		for (ClaimOffer o : s.claimBlocks) {
			if (!o.icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + o.icon + "\" isn't an item id (like minecraft:grass_block).";
			if (!o.iconModel.isEmpty() && !o.iconModel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + o.iconModel + "\" isn't an item model id (like eden:coin).";
			if (!o.iconTexture.isEmpty() && Pets.texture(o.iconTexture) == null) return "The head texture of a claim block listing " + TEXTURE_HELP;
			if (o.price < 1 || o.price > 10_000_000) return "Claim block prices are 1 to 10,000,000.";
			if (o.blocks < 1 || o.blocks > 1_000_000) return "Claim block listings have 1 to 1,000,000 blocks.";
			if (o.saleEnds < 0 || o.saleEnds > System.currentTimeMillis() + 3660 * 86_400_000L) return "Claim block listings end within ten years, or never.";
		}
		if (s.claimBlocksMax < 0 || s.claimBlocksMax > 100_000_000) return "The most claim blocks to buy are 0 (no limit) to 100,000,000.";
		if (s.serverName.length() > 32) return "The server shop's seller name is at most 32 characters.";
		if (s.commandOffers.size() > 50) return "At most 50 command listings.";
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (CommandOffer o : s.commandOffers) {
			if (!ids.add(o.id)) return "Two command listings have the same id.";
			if (o.name.isEmpty() || o.name.length() > 40) return "Command listing names are 1 to 40 characters.";
			if (o.description.length() > 200) return "The description of " + o.name + " is longer than 200 characters.";
			if (!o.icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + o.icon + "\" isn't an item id (like minecraft:paper).";
			if (!o.iconModel.isEmpty() && !o.iconModel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + o.iconModel + "\" isn't an item model id (like eden:coin).";
			if (!o.iconTexture.isEmpty() && Pets.texture(o.iconTexture) == null) return "The head texture of " + o.name + " " + TEXTURE_HELP;
			if (o.price < 1 || o.price > 10_000_000) return "The price of " + o.name + " is 1 to 10,000,000.";
			if (o.commands.isEmpty() || o.commands.size() > 20) return o.name + " needs 1 to 20 commands.";
			for (String c : o.commands) if (c.length() > 2000 || c.contains("\n")) return "A command of " + o.name + " is too long.";
			if (o.perPlayer < 0 || o.perPlayer > 100_000) return "How many of " + o.name + " a player can buy is 0 (no limit) to 100,000.";
			if (o.saleEnds < 0 || o.saleEnds > System.currentTimeMillis() + 3660 * 86_400_000L) return "Command listings end within ten years, or never.";
		}
		return null;
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings().fill();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings().fill();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings().fill();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		writeJson(file, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings));
	}

	/** An item as the market keeps it: the stack (count 1) as JSON, plus what the web shows. */
	public static final class Item {
		/** The whole stack as the game writes it (id, components), for giving it back exactly. */
		public String stack;
		public String id;
		public String name;
		public List<String> lore = new ArrayList<>();
		public List<String> enchantments = new ArrayList<>();
		public int maxStack = 64;
	}

	public static final class Listing {
		public String id;
		public String seller;
		public String sellerName;
		public Item item;
		/** Items left to buy (any number, several stacks). */
		public int amount;
		/** Price per {@link #per} items, in the currency. */
		public int price;
		/** How many items the price is for: bought in lots of this many (missing in older files: 1). */
		public int per = 1;
		public long created;
		public long expires;
		public long rev;

		public int lot() {
			return Math.max(1, per);
		}
	}

	/** An item waiting in a mailbox (bought items that didn't fit, unsold items back from the market). */
	public static final class Parcel {
		public Item item;
		public int amount;
	}

	/** A command listing bought on the website: its commands run when the player claims it in game (kept as bought). */
	public static final class Order {
		public String offer;
		public String name;
		public String icon;
		public int times;
		public List<String> commands = new ArrayList<>();
		public long bought;
	}

	/** Most a balance can hold (far above anything real; keeps sums from overflowing). */
	public static final long MAX_BALANCE = 1_000_000_000_000L;

	public static final class Mailbox {
		public String name;
		/** Currency owed from sales in files before the bank (moved into {@link #balance} on load). */
		public int currency;
		/** Currency in the player's bank: deposited, or earned from sales. Spent first when buying. */
		public long balance;
		public List<Parcel> parcels = new ArrayList<>();
		/** Command listings bought on the website, waiting to be claimed in game. */
		public List<Order> orders = new ArrayList<>();
		public long rev;

		/** Nothing to collect (the balance isn't mail). */
		public boolean isEmpty() {
			return currency <= 0 && parcels.isEmpty() && orders.isEmpty();
		}
	}

	public static final class Sale {
		public String listing;
		public String itemName;
		public String itemId;
		public String seller;
		public String sellerName;
		public String buyer;
		public String buyerName;
		public int amount;
		public int total;
		public long time;
		public long rev;
	}

	public static final class Data {
		public String worldId = UUID.randomUUID().toString();
		public long rev;
		public long prunedRev;
		public Map<String, Listing> listings = new LinkedHashMap<>();
		/** The listing time (days) the listings' end dates follow; 0 in files from before it was kept. */
		public int listingDays;
		public Map<String, Mailbox> mail = new LinkedHashMap<>();
		public List<Sale> sales = new ArrayList<>();
		public Map<String, Long> tombstones = new LinkedHashMap<>();
		/** How many of each command listing (by id) each player (uuid) bought, for {@link CommandOffer#perPlayer}. */
		public Map<String, Map<String, Integer>> serverBought = new LinkedHashMap<>();

		public Data fill() {
			if (worldId == null || worldId.isBlank()) worldId = UUID.randomUUID().toString();
			if (listings == null) listings = new LinkedHashMap<>();
			if (mail == null) mail = new LinkedHashMap<>();
			if (sales == null) sales = new ArrayList<>();
			if (tombstones == null) tombstones = new LinkedHashMap<>();
			if (serverBought == null) serverBought = new LinkedHashMap<>();
			mail.values().removeIf(m -> m == null);
			for (Mailbox m : mail.values()) {
				if (m.parcels == null) m.parcels = new ArrayList<>();
				if (m.orders == null) m.orders = new ArrayList<>();
				m.orders.removeIf(o -> o == null || o.commands == null || o.times < 1);
				// Earnings waiting before the bank existed go into the balance.
				if (m.currency > 0) {
					m.balance = Math.min(MAX_BALANCE, Math.max(0, m.balance) + m.currency);
					m.currency = 0;
					m.rev = bump();
				}
			}
			listings.values().removeIf(l -> l == null || l.id == null || l.item == null || l.amount <= 0);
			return this;
		}

		public long bump() {
			return ++rev;
		}

		public Mailbox mailbox(String uuid, String name) {
			Mailbox m = mail.computeIfAbsent(uuid, k -> new Mailbox());
			if (name != null) m.name = name;
			return m;
		}

		/** The player's balance (0 when they have no mailbox yet). */
		public long balance(String uuid) {
			Mailbox m = mail.get(uuid);
			return m == null ? 0 : m.balance;
		}

		/** Adds to the player's balance (deposits, sales); false when it would go over {@link #MAX_BALANCE}. */
		public boolean credit(String uuid, String name, long amount) {
			Mailbox m = mailbox(uuid, name);
			if (amount < 0 || m.balance + amount > MAX_BALANCE) return false;
			m.balance += amount;
			m.rev = bump();
			return true;
		}

		/** Takes from the player's balance (spending, withdrawing); false when there isn't that much. */
		public boolean debit(String uuid, long amount) {
			Mailbox m = mail.get(uuid);
			if (amount < 0 || m == null || m.balance < amount) return false;
			m.balance -= amount;
			m.rev = bump();
			return true;
		}

		/** A command listing bought on the website waits in the mailbox until it's claimed in game. */
		public void order(String uuid, String name, CommandOffer o, int times, long now) {
			Mailbox m = mailbox(uuid, name);
			Order order = new Order();
			order.offer = o.id;
			order.name = o.name;
			order.icon = o.icon;
			order.times = times;
			order.commands = new ArrayList<>(o.commands);
			order.bought = now;
			m.orders.add(order);
			m.rev = bump();
		}

		/** How many of this command listing the player bought. */
		public int serverBought(String offer, String uuid) {
			Map<String, Integer> m = serverBought.get(offer);
			return m == null ? 0 : m.getOrDefault(uuid, 0);
		}

		/**
		 * A purchase from the server (the currency is already taken): counted for the per-player limit when it's a
		 * command listing, and kept in the sales list with no seller.
		 */
		public Sale serverSale(String offer, boolean command, String itemName, String itemId, String sellerName, String buyer, String buyerName, int amount, int total, long now) {
			if (command) serverBought.computeIfAbsent(offer, k -> new LinkedHashMap<>()).merge(buyer, amount, Integer::sum);
			Sale sale = new Sale();
			sale.listing = offer;
			sale.itemName = itemName;
			sale.itemId = itemId;
			sale.sellerName = sellerName;
			sale.buyer = buyer;
			sale.buyerName = buyerName;
			sale.amount = amount;
			sale.total = total;
			sale.time = now;
			sale.rev = bump();
			sales.add(sale);
			while (sales.size() > SALES) sales.remove(0);
			return sale;
		}

		public List<Listing> of(String seller) {
			List<Listing> out = new ArrayList<>();
			for (Listing l : listings.values()) if (seller.equals(l.seller)) out.add(l);
			return out;
		}

		private void remove(Listing l) {
			listings.remove(l.id);
			tombstones.put(l.id, bump());
			if (tombstones.size() > TOMBSTONES) {
				var it = tombstones.entrySet().iterator();
				while (tombstones.size() > TOMBSTONES && it.hasNext()) {
					prunedRev = Math.max(prunedRev, it.next().getValue());
					it.remove();
				}
			}
		}

		/** Lists items (already taken from the seller). Error for the player, or null. */
		public String list(String seller, String sellerName, Item item, int amount, int price, int per, Settings s, long now) {
			if (amount < 1) return "List at least one item.";
			if (price < 1 || price > s.maxPrice) return "The price is 1 to " + s.maxPrice + ".";
			if (per < 1 || per > amount) return "The price is for 1 to " + amount + " items.";
			if (amount % per != 0) return "Sell a multiple of " + per + " (they're bought " + per + " at a time).";
			if (s.bannedItems.contains(item.id)) return "That item can't be sold here.";
			if (of(seller).size() >= s.maxListings) return "You already have " + s.maxListings + " listings.";
			Listing l = new Listing();
			l.id = UUID.randomUUID().toString();
			l.seller = seller;
			l.sellerName = sellerName;
			l.item = item;
			l.amount = amount;
			l.price = price;
			l.per = per;
			l.created = now;
			l.expires = now + s.listingDays * 86_400_000L;
			l.rev = bump();
			listings.put(l.id, l);
			tombstones.remove(l.id);
			return null;
		}

		/** The seller changes the price or how many are bought at a time. Error for the player, or null. */
		public String edit(Listing l, int price, int per, Settings s) {
			if (price < 1 || price > s.maxPrice) return "The price is 1 to " + s.maxPrice + ".";
			if (per < 1 || per > l.amount) return "The price is for 1 to " + l.amount + " items.";
			if (l.amount % per != 0) return l.amount + " are left: \"For how many\" has to fit into that (they're bought " + per + " at a time).";
			l.price = price;
			l.per = per;
			l.rev = bump();
			return null;
		}

		/** More of the same item (already taken from the seller); the listing runs for its full time again. Error, or null. */
		public String restock(Listing l, int amount, Settings s, long now) {
			if (amount < 1) return "Add at least one item.";
			if (amount % l.lot() != 0) return "Add a multiple of " + l.lot() + " (they're bought " + l.lot() + " at a time).";
			if ((long) l.amount + amount > Integer.MAX_VALUE) return "That's too many.";
			l.amount += amount;
			l.expires = now + s.listingDays * 86_400_000L;
			l.rev = bump();
			return null;
		}

		/** A purchase of whole lots (the currency is already taken from the buyer): the seller is paid into their balance. */
		public Sale buy(Listing l, String buyer, String buyerName, int amount, long now) {
			l.amount -= amount;
			int total = amount / l.lot() * l.price;
			Mailbox m = mailbox(l.seller, l.sellerName);
			m.balance = Math.min(MAX_BALANCE, m.balance + total);
			m.rev = bump();
			Sale sale = new Sale();
			sale.listing = l.id;
			sale.itemName = l.item.name;
			sale.itemId = l.item.id;
			sale.seller = l.seller;
			sale.sellerName = l.sellerName;
			sale.buyer = buyer;
			sale.buyerName = buyerName;
			sale.amount = amount;
			sale.total = total;
			sale.time = now;
			sale.rev = bump();
			sales.add(sale);
			while (sales.size() > SALES) sales.remove(0);
			if (l.amount <= 0) remove(l);
			else l.rev = bump();
			return sale;
		}

		/** How many items the predicate counts as the same wait in the player's mailbox. */
		public int mailboxCount(String uuid, java.util.function.Predicate<Item> same) {
			Mailbox m = mail.get(uuid);
			if (m == null) return 0;
			long n = 0;
			for (Parcel p : m.parcels) if (same.test(p.item)) n += p.amount;
			return (int) Math.min(Integer.MAX_VALUE, n);
		}

		/** Takes up to amount such items out of the player's mailbox; returns how many it took. */
		public int takeFromMailbox(String uuid, java.util.function.Predicate<Item> same, int amount) {
			Mailbox m = mail.get(uuid);
			if (m == null || amount <= 0) return 0;
			int left = amount;
			for (var it = m.parcels.iterator(); it.hasNext() && left > 0;) {
				Parcel p = it.next();
				if (!same.test(p.item)) continue;
				int t = Math.min(left, p.amount);
				p.amount -= t;
				left -= t;
				if (p.amount <= 0) it.remove();
			}
			if (left < amount) m.rev = bump();
			return amount - left;
		}

		/**
		 * Price, lot size and amount at once (the website's listing editor), with the menus' rules. Fewer items: the
		 * rest goes to the seller's mailbox. More: the caller has already taken them from the seller, and the listing
		 * runs for its full time again, like "Add more". Error for the player, or null.
		 */
		public String update(Listing l, int amount, int price, int per, Settings s, long now) {
			if (amount < 1) return "Keep at least one item on the market, or take the listing back.";
			if (price < 1 || price > s.maxPrice) return "The price is 1 to " + s.maxPrice + ".";
			if (per < 1 || per > amount) return "The price is for 1 to " + amount + " items.";
			if (amount % per != 0) return amount + " don't split into lots of " + per + ": make the amount a multiple of \"For how many\".";
			int spare = l.amount - amount;
			if (spare > 0) {
				Mailbox m = mailbox(l.seller, l.sellerName);
				Parcel p = new Parcel();
				p.item = l.item;
				p.amount = spare;
				m.parcels.add(p);
				m.rev = bump();
			} else if (spare < 0) {
				l.expires = now + s.listingDays * 86_400_000L;
			}
			l.amount = amount;
			l.price = price;
			l.per = per;
			l.rev = bump();
			return null;
		}

		/**
		 * The settings' listing time changed: every listing moves by the difference (at least an hour left). The
		 * first time, with the old time unknown, each listing runs at least the new time from when it was put up.
		 * True when something changed.
		 */
		public boolean applyListingDays(int days, long now) {
			if (days <= 0 || days == listingDays) return false;
			long day = 86_400_000L;
			for (Listing l : listings.values()) {
				long next = listingDays <= 0 ? Math.max(l.expires, l.created + days * day) : Math.max(now + 3_600_000L, l.expires + (long) (days - listingDays) * day);
				if (next != l.expires) {
					l.expires = next;
					l.rev = bump();
				}
			}
			listingDays = days;
			return true;
		}

		/** Sends some of a listing back to the seller's mailbox (the spare items when the lot size changes). */
		public void trim(Listing l, int spare) {
			if (spare <= 0 || spare >= l.amount) return;
			l.amount -= spare;
			Mailbox m = mailbox(l.seller, l.sellerName);
			Parcel p = new Parcel();
			p.item = l.item;
			p.amount = spare;
			m.parcels.add(p);
			m.rev = bump();
			l.rev = bump();
		}

		/** Takes a listing off the market; what's left goes back to the seller's mailbox. */
		public void withdraw(Listing l) {
			if (l.amount > 0) {
				Mailbox m = mailbox(l.seller, l.sellerName);
				Parcel p = new Parcel();
				p.item = l.item;
				p.amount = l.amount;
				m.parcels.add(p);
				m.rev = bump();
			}
			remove(l);
		}

		/** Listings past their time go back to their sellers. Returns how many. */
		public int expire(long now) {
			List<Listing> old = new ArrayList<>();
			for (Listing l : listings.values()) if (l.expires < now) old.add(l);
			old.forEach(this::withdraw);
			return old.size();
		}

		public void mailChanged(Mailbox m) {
			m.rev = bump();
		}

		/** Changes since a revision for the panel's copy (everything when it's too old). */
		public Map<String, Object> changes(long since) {
			boolean full = since <= 0 || since < prunedRev || since > rev;
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("worldId", worldId);
			out.put("rev", rev);
			out.put("full", full);
			List<Listing> changed = new ArrayList<>();
			for (Listing l : listings.values()) if (full || l.rev > since) changed.add(l);
			out.put("listings", changed);
			List<String> removed = new ArrayList<>();
			if (!full) tombstones.forEach((id, r) -> {
				if (r > since) removed.add(id);
			});
			out.put("removed", removed);
			List<Sale> newSales = new ArrayList<>();
			for (Sale s : sales) if (full || s.rev > since) newSales.add(s);
			out.put("sales", newSales);
			// Mailboxes are few and small: always all of them (for the panel's database copy).
			out.put("mail", mail);
			return out;
		}
	}

	public static Data readData(Path file) throws IOException {
		if (!Files.exists(file)) return new Data().fill();
		Data d = Json.GSON.fromJson(Files.readString(file), Data.class);
		return d == null ? new Data().fill() : d.fill();
	}

	public static void writeData(Path file, Data data) throws IOException {
		writeJson(file, Json.GSON.toJson(data));
	}

	private static void writeJson(Path file, String json) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
