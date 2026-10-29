package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Points: one balance per player, earned through Rewards rules (the "points" reward) and the /points commands (also
 * from command blocks, for games built by hand), spent at reward shops: NPCs placed in the world, each with its own
 * skin and list of rewards. Shops are kept in config/nicecontrolcenter-points.json (panel: Games → Reward shops),
 * balances by the mod in the world folder (nicecontrolcenter-points-data.json). Minecraft-free so both compile it.
 */
public final class Points {
	public static final String FILE = "nicecontrolcenter-points.json";
	public static final String DATA = "nicecontrolcenter-points-data.json";
	/** Changes kept per player for /points and the website. */
	public static final int HISTORY = 30;

	private Points() {
	}

	public static final class Settings {
		public boolean enabled = true;
		/** What they're called ("Points", "Tickets"…). */
		public String name = "Points";
		public List<Shop> shops = new ArrayList<>();

		public Shop shop(String idOrName) {
			for (Shop s : shops) if (s.id.equals(idOrName) || s.name.equalsIgnoreCase(idOrName)) return s;
			return null;
		}

		public Settings fill() {
			if (name == null || name.isBlank()) name = "Points";
			if (shops == null) shops = new ArrayList<>();
			shops.removeIf(s -> s == null);
			Set<String> ids = new HashSet<>();
			for (Shop s : shops) {
				if (s.id == null || s.id.isBlank() || !ids.add(s.id)) {
					s.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(s.id);
				}
				if (s.name == null) s.name = "";
				if (s.title == null) s.title = "";
				if (s.texture == null) s.texture = "";
				if (s.greeting == null) s.greeting = "";
				if (s.npcs == null) s.npcs = new ArrayList<>();
				s.npcs.removeIf(p -> p == null || p.dim == null);
				if (s.entries == null) s.entries = new ArrayList<>();
				s.entries.removeIf(e -> e == null);
				Set<String> eids = new HashSet<>();
				for (Entry e : s.entries) {
					if (e.id == null || e.id.isBlank() || !eids.add(e.id)) {
						e.id = UUID.randomUUID().toString().substring(0, 8);
						eids.add(e.id);
					}
					if (e.name == null) e.name = "";
					if (e.description == null) e.description = "";
					if (e.icon == null || e.icon.isBlank()) e.icon = "minecraft:chest";
					if (e.iconTexture == null) e.iconTexture = "";
					e.price = Math.max(0, Math.min(1_000_000_000, e.price));
					e.perPlayer = Math.max(0, Math.min(1_000_000, e.perPlayer));
					e.stock = Math.max(0, Math.min(1_000_000, e.stock));
					if (e.items == null) e.items = new ArrayList<>();
					e.items.removeIf(i -> i == null || i.item == null || i.item.stack == null);
					for (Stack i : e.items) i.count = Math.max(1, Math.min(6400, i.count));
					if (e.commands == null) e.commands = new ArrayList<>();
					e.commands.removeIf(c -> c == null || c.isBlank());
					if (e.actions == null) e.actions = new ArrayList<>();
					e.actions.removeIf(a -> a == null || a.type == null);
					for (Rewards.Action a : e.actions) if (a.target == null) a.target = "";
				}
			}
			return this;
		}
	}

	/** A reward shop: its NPCs (all the same skin and list) and what it sells. */
	public static final class Shop {
		public String id = "";
		/** One word, for the commands. */
		public String name = "";
		/** Shown above the NPCs and on top of the dialog; empty = the name. */
		public String title = "";
		/** The NPCs' skin: a textures.minecraft.net URL, its hash, or the base64 value. */
		public String texture = "";
		/** A line on top of the shop dialog. */
		public String greeting = "";
		/** The NPCs turn their head to players close by. */
		public boolean lookAtPlayers = true;
		/** Where its NPCs stand. */
		public List<Games.Pos> npcs = new ArrayList<>();
		public List<Entry> entries = new ArrayList<>();

		public String shown() {
			return title.isBlank() ? name : title;
		}

		public Entry entry(String id) {
			for (Entry e : entries) if (e.id.equals(id)) return e;
			return null;
		}
	}

	/** One reward in a shop: what it costs, how often it can be had, and what it gives. */
	public static final class Entry {
		public String id = "";
		public String name = "";
		public String description = "";
		/** Its icon: an item id, or a head skin (iconTexture, wins when set). */
		public String icon = "minecraft:chest";
		public String iconTexture = "";
		public int price = 10;
		/** Times one player can buy it (0 = no limit). */
		public int perPlayer = 0;
		/** Times it can be bought by everyone together (0 = no limit). */
		public int stock = 0;
		/** Items it gives (set in game from the hand: /points shop item). */
		public List<Stack> items = new ArrayList<>();
		/** Run as the server at the player ({player}, {uuid}), without /. */
		public List<String> commands = new ArrayList<>();
		/** Non-item rewards, like in Rewards rules (flight, claim blocks, pets, perks, coupons…). */
		public List<Rewards.Action> actions = new ArrayList<>();
	}

	/** An item and how many of it. */
	public static final class Stack {
		public Market.Item item;
		public int count = 1;
	}

	/** One player's points. */
	public static final class Account {
		public String name;
		public long balance;
		/** Everything they ever got. */
		public long earned;
		/** Entry id → times bought. */
		public Map<String, Integer> bought = new LinkedHashMap<>();
		/** Newest last. */
		public List<Change> history = new ArrayList<>();
	}

	public static final class Change {
		public long time;
		public long amount;
		public String why;

		public Change() {
		}

		public Change(long time, long amount, String why) {
			this.time = time;
			this.amount = amount;
			this.why = why;
		}
	}

	public static final class Data {
		public Map<String, Account> accounts = new LinkedHashMap<>();
		/** Entry id → times bought by everyone. */
		public Map<String, Integer> sold = new LinkedHashMap<>();

		public Data fill() {
			if (accounts == null) accounts = new LinkedHashMap<>();
			if (sold == null) sold = new LinkedHashMap<>();
			for (Account a : accounts.values()) {
				if (a.bought == null) a.bought = new LinkedHashMap<>();
				if (a.history == null) a.history = new ArrayList<>();
			}
			return this;
		}
	}

	/** "5 points", "1 point" with the server's own name for them. */
	public static String amount(Settings s, long n) {
		String plural = s.name.strip();
		String one = plural.toLowerCase(Locale.ROOT).endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
		return String.format(Locale.ROOT, "%,d", n) + " " + (n == 1 || n == -1 ? one : plural).toLowerCase(Locale.ROOT);
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Shop shop : s.shops) {
			if (!shop.name.matches("[A-Za-z0-9_-]{1,32}")) return "A shop needs a one-word name (letters, digits, - and _).";
			if (!names.add(shop.name.toLowerCase(Locale.ROOT))) return "Two shops are called \"" + shop.name + "\".";
			if (shop.title.length() > 64 || shop.greeting.length() > 300) return "The title or greeting of " + shop.name + " is too long.";
			if (shop.entries.size() > 100) return shop.name + " has more than 100 rewards.";
			for (Entry e : shop.entries) {
				if (e.name.isBlank() || e.name.length() > 64) return "Every reward in " + shop.name + " needs a name (up to 64 characters).";
				if (e.description.length() > 300) return "The description of " + e.name + " is too long.";
				if (e.items.isEmpty() && e.commands.isEmpty() && e.actions.isEmpty()) return e.name + " in " + shop.name + " gives nothing yet: add items, commands or rewards.";
				for (String c : e.commands) if (c.length() > 2000 || c.contains("\n")) return "A command of " + e.name + " is too long.";
			}
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
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static Data readData(Path file) {
		try {
			if (Files.exists(file)) {
				Data d = Json.GSON.fromJson(Files.readString(file), Data.class);
				if (d != null) return d.fill();
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return new Data();
	}
}
