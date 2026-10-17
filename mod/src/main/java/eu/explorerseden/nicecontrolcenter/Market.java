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
				o.id = uuidOr(o.id);
			}
			if (commandOffers == null) commandOffers = new ArrayList<>();
			commandOffers = new ArrayList<>(commandOffers.stream().filter(o -> o != null).toList());
			for (CommandOffer o : commandOffers) {
				o.id = uuidOr(o.id);
				o.icon = o.icon == null || o.icon.isBlank() ? "minecraft:paper" : id(o.icon);
				o.iconModel = o.iconModel == null || o.iconModel.isBlank() ? "" : id(o.iconModel);
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

	private static String id(String s) {
		String t = s.strip().toLowerCase(Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

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

	public static final class Mailbox {
		public String name;
		/** Currency owed (sales). */
		public int currency;
		public List<Parcel> parcels = new ArrayList<>();
		public long rev;

		public boolean isEmpty() {
			return currency <= 0 && parcels.isEmpty();
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
			listings.values().removeIf(l -> l == null || l.id == null || l.item == null || l.amount <= 0);
			return this;
		}

		long bump() {
			return ++rev;
		}

		public Mailbox mailbox(String uuid, String name) {
			Mailbox m = mail.computeIfAbsent(uuid, k -> new Mailbox());
			if (name != null) m.name = name;
			return m;
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

		/** A purchase of whole lots (the currency is already taken from the buyer): the seller is paid into their mailbox. */
		public Sale buy(Listing l, String buyer, String buyerName, int amount, long now) {
			l.amount -= amount;
			int total = amount / l.lot() * l.price;
			Mailbox m = mailbox(l.seller, l.sellerName);
			m.currency += total;
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
