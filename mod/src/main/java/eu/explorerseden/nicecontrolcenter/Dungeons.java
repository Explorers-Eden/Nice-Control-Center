package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Instanced dungeons, kept in config/nicecontrolcenter-dungeons.json: admins build a dungeon anywhere and save it
 * with /dungeon (the blocks go to the world folder as pieces); players step on a pad and get a fresh copy of a
 * random dungeon from the pad's pool in the dungeon dimension, wiped again when they're done. Minecraft-free so
 * both compile it.
 */
public final class Dungeons {
	public static final String FILE = "nicecontrolcenter-dungeons.json";
	/**
	 * Rogue runs live in a file of their own: the runs, their rooms and room groups, pads and waiting areas, and the
	 * shared rogue settings (boons, shop, unlocks). In memory they sit next to the dungeons, marked rogue, and nothing
	 * of one kind is seen by the other (rooms, groups, pads and pools are looked up per kind).
	 */
	public static final String ROGUE_FILE = "nicecontrolcenter-rogue.json";
	/** The void dimension the copies are made in (shipped with the mod). */
	public static final String DIMENSION = "nicecontrolcenter:dungeons";
	/** Pieces are 16 × 16 columns (one chunk wide), as high as the box (its two corners). */
	public static final int PIECE = 16;
	/** Markers: data-mode structure blocks in the build, with one of these as their text. */
	public static final List<String> MARKERS = List.of("start", "exit", "checkpoint", "mob", "boss", "door", "spawn", "shop");
	/** Rogue runs: the room types a door can lead to (rooms come from the group set for the type). */
	public static final List<String> ROGUE_TYPES = List.of("fight", "elite", "boss", "treasure", "shop", "rest");
	/** What a boon does: effect, attribute (modifier), item, heal, command. */
	public static final List<String> BOON_KINDS = List.of("attribute", "effect", "item", "heal", "command");
	/** What an unlock gives every run: coins at the start, a boon at the start, boon rerolls, one more boon to choose from. */
	public static final List<String> UNLOCK_KINDS = List.of("coins", "boon", "reroll", "choice");

	/** A boon: something a player gets for the rest of a rogue run (picked after a room, or bought in a shop). */
	public static final class Boon {
		public String id = "";
		public boolean enabled = true;
		public String name = "";
		/** An item id for its look in menus. */
		public String icon = "minecraft:nether_star";
		public String kind = "attribute";
		/** attribute: its id (max_health…); effect: its id (strength…); item: as in /give ("golden_apple"); command: without / ({player}). */
		public String target = "";
		/** attribute: per stack; item: how many; heal: health points (2 = a heart). Effects: the level grows with each stack. */
		public double amount = 1;
		/** attribute: "add" (a flat amount) or "percent" (0.1 = +10 %). */
		public String op = "add";
		/** How often one player can take it in a run (0 = no limit). */
		public int maxStacks = 3;
		/** How often it's offered (more = more often). */
		public int weight = 10;
		/** Shown in menus; empty = made from the above. */
		public String description = "";
	}

	/** What clearing a room can give (one rolled per room for the party): boon (each picks one), coins, max health, armor. */
	public static final List<String> REWARD_KINDS = List.of("boon", "coins", "health", "armor");

	/** One kind of room reward and how likely it is. */
	public static final class RoomReward {
		public boolean enabled = true;
		public String kind = "boon";
		public int weight = 10;
		/** coins: coins; health: max health points (2 = a heart); armor: armor points (2 = a chestplate icon); for the run. */
		public int amount;
		/** This many more per room after the first. */
		public int perRoom;
	}

	private static RoomReward reward(String kind, int weight, int amount, int perRoom) {
		RoomReward r = new RoomReward();
		r.kind = kind;
		r.weight = weight;
		r.amount = amount;
		r.perRoom = perRoom;
		return r;
	}

	/** Something rogue shops sell: a boon at a price in run coins (more the deeper the run). */
	public static final class ShopEntry {
		public String id = "";
		public boolean enabled = true;
		public String boon = "";
		public int price = 10;
	}

	/** Bought with points between runs, a level at a time; works in every rogue run. */
	public static final class Unlock {
		public String id = "";
		public boolean enabled = true;
		public String name = "";
		public String icon = "minecraft:experience_bottle";
		public String kind = "coins";
		/** kind boon: the boon each level hands out at the start. */
		public String boon = "";
		/** Shown in the unlocks menu; empty = made from the kind. */
		public String description = "";
		/** kind coins: coins per level. */
		public int amount = 5;
		/** Points for the first level; each next level costs this much more (level 2 = twice). */
		public int cost = 50;
		public int maxLevel = 3;
	}

	/** Rogue runs' shared settings: coins, room effects, the boons, the shop and the unlocks. */
	public static final class Rogue {
		/** Coins every player still in gets per foe; elite rooms pay this many times as much, bosses a fixed amount. */
		public int coinsPerKill = 1;
		public int eliteCoins = 2;
		public int bossCoins = 15;
		/** Rest rooms heal this % of max health. */
		public int restHeal = 50;
		/** Rest rooms: hunger points filled (0-20), saturation given, harmful effects taken away. */
		public int restFood = 6;
		public int restSaturation = 0;
		public boolean restCleanse = true;
		/** Rest rooms: effects given ("minecraft:regeneration 10 1": id, seconds, level). */
		public List<String> restEffects = new ArrayList<>(List.of("minecraft:regeneration 5 1"));
		/** Rest rooms: told to each player on arriving (empty = the default line); commands run as the server per player ({player}). */
		public String restMessage = "";
		public List<String> restCommands = new ArrayList<>();
		/** The unlocks menu: its title and the line on top. */
		public String unlocksTitle = "Rogue unlocks";
		public String unlocksGreeting = "Unlocks help in every rogue run, a level at a time.";
		/** Shop prices grow by this % per room. */
		public int priceGrowth = 10;
		/** Boons to choose from after a room; seconds to choose before one is picked for them. */
		public int choices = 3;
		public int pickSeconds = 30;
		/** What clearing a fight, elite or boss room gives (treasure rooms always give a boon choice). */
		public List<RoomReward> rewards = new ArrayList<>(List.of(reward("boon", 60, 0, 0), reward("coins", 20, 8, 2), reward("health", 10, 2, 0), reward("armor", 10, 2, 0)));
		/** The shop NPC on a shop room's shop marker: its name and skin (a player name, a skin link or base64; empty = default). */
		public String shopName = "Merchant";
		public String shopSkin = "";
		public List<Boon> boons = defaultBoons();
		public List<ShopEntry> shop = defaultShop();
		public List<Unlock> unlocks = defaultUnlocks();

		public Boon boon(String id) {
			for (Boon b : boons) if (b.id.equals(id)) return b;
			return null;
		}
	}

	private static Boon boon(String id, String name, String icon, String kind, String target, double amount, String op, int max, int weight) {
		Boon b = new Boon();
		b.id = id;
		b.name = name;
		b.icon = icon;
		b.kind = kind;
		b.target = target;
		b.amount = amount;
		b.op = op;
		b.maxStacks = max;
		b.weight = weight;
		return b;
	}

	static List<Boon> defaultBoons() {
		return new ArrayList<>(List.of(
				boon("vitality", "Vitality", "minecraft:golden_apple", "attribute", "minecraft:max_health", 4, "add", 5, 10),
				boon("might", "Might", "minecraft:iron_sword", "attribute", "minecraft:attack_damage", 1, "add", 5, 10),
				boon("swiftness", "Swiftness", "minecraft:feather", "attribute", "minecraft:movement_speed", 0.08, "percent", 3, 8),
				boon("plating", "Plating", "minecraft:iron_chestplate", "attribute", "minecraft:armor", 2, "add", 5, 8),
				boon("steadfast", "Steadfast", "minecraft:anvil", "attribute", "minecraft:knockback_resistance", 0.2, "add", 3, 5),
				boon("regeneration", "Regeneration", "minecraft:ghast_tear", "effect", "minecraft:regeneration", 1, "add", 2, 4),
				boon("resistance", "Resistance", "minecraft:shield", "effect", "minecraft:resistance", 1, "add", 2, 3),
				boon("fireproof", "Fireproof", "minecraft:magma_cream", "effect", "minecraft:fire_resistance", 1, "add", 1, 4),
				boon("snack", "Golden snack", "minecraft:golden_apple", "item", "minecraft:golden_apple", 2, "add", 0, 6),
				boon("patch_up", "Patch up", "minecraft:glistering_melon_slice", "heal", "", 10, "add", 0, 8)));
	}

	private static ShopEntry shopEntry(String boon, int price) {
		ShopEntry e = new ShopEntry();
		e.boon = boon;
		e.price = price;
		return e;
	}

	static List<ShopEntry> defaultShop() {
		return new ArrayList<>(List.of(shopEntry("patch_up", 5), shopEntry("snack", 8), shopEntry("vitality", 12), shopEntry("might", 15), shopEntry("plating", 12),
				shopEntry("regeneration", 20)));
	}

	private static Unlock unlock(String id, String name, String icon, String kind, String boon, int amount, int cost, int max) {
		Unlock u = new Unlock();
		u.id = id;
		u.name = name;
		u.icon = icon;
		u.kind = kind;
		u.boon = boon;
		u.amount = amount;
		u.cost = cost;
		u.maxLevel = max;
		return u;
	}

	static List<Unlock> defaultUnlocks() {
		return new ArrayList<>(List.of(
				unlock("purse", "Coin purse", "minecraft:gold_nugget", "coins", "", 5, 30, 3),
				unlock("tough_start", "Tough start", "minecraft:golden_apple", "boon", "vitality", 0, 60, 2),
				unlock("second_thoughts", "Second thoughts", "minecraft:clock", "reroll", "", 0, 50, 2),
				unlock("wider_choice", "Wider choice", "minecraft:chest", "choice", "", 0, 120, 1)));
	}

	/** A marker in a saved dungeon, relative to the box's lowest corner. */
	public static final class Marker {
		public String type;
		/** What follows the type ("zombie x3", an entity id and its data…). */
		public String arg = "";
		public int x;
		public int y;
		public int z;
	}

	/**
	 * A jigsaw block in a dungeon or room, relative to the box's lowest corner. One with a pool is a spot that gets a
	 * room of that group; a room's connector whose name a spot targets is where the room joins it.
	 */
	public static final class Connector {
		public int x;
		public int y;
		public int z;
		/** Where it faces (north, east, south, west, up, down) and its top (up for sideways ones). */
		public String facing = "north";
		public String top = "up";
		/** Up and down connectors: "rollable" (any turn) or "aligned" (tops match). */
		public String joint = "rollable";
		public String name = "";
		public String target = "";
		/** The room group (the jigsaw's target pool without "minecraft:"); empty = not a spot. */
		public String pool = "";
		/** The block it turns into once the copy is made. */
		public String finalState = "minecraft:air";

		public boolean spot() {
			return pool != null && !pool.isEmpty() && !"empty".equals(pool);
		}
	}

	/** A prebuilt room: copied into spots of its groups (the tunables are set in the panel). */
	public static final class Room {
		public String id = "";
		public String name = "";
		/** A rogue run room (kept in the rogue file; only rogue runs use it). */
		public boolean rogue;
		public List<String> groups = new ArrayList<>();
		public boolean enabled = true;
		/** How often it's picked in its group, against the others (1–100). */
		public int weight = 10;
		/** %: the chance it's considered at all for a spot (else the next room is tried). */
		public int chance = 100;
		/** At most this many in one copy; 0 = no limit. */
		public int maxPerCopy;
		/** Where it was built (so it can be saved again from the panel). */
		public String sourceDim;
		public int[] from;
		public int[] to;
		/** What the last save found (filled by the mod). */
		public int sizeX;
		public int sizeY;
		public int sizeZ;
		public List<Marker> markers = new ArrayList<>();
		public List<Connector> connectors = new ArrayList<>();
		public long savedAt;
		public String savedBy;

		public boolean saved() {
			return sizeX > 0 && savedAt > 0;
		}
	}

	/** A room group's tunables (made when a block or room names one). */
	public static final class Group {
		public String name = "";
		/** A rogue run room group (rogue groups and dungeon groups can share a name). */
		public boolean rogue;
		/** %: the chance a spot of this group gets a room at all (else it stays closed). */
		public int fillChance = 100;
		/** Rooms of this group come only this many levels in (0 = right away; 1 = only inside another room…). */
		public int minDepth;
	}

	public static final class Dungeon {
		public String id = "";
		public String name = "";
		/**
		 * "dungeon": a built box with rooms in it; "rogue": no box of its own, a chain of rooms one at a time (clear
		 * the room, pick a door, the next one is harder) until everyone is out.
		 */
		public String mode = "dungeon";
		/** Rogue: the group of the first room (a safe room, its doors open at once); empty = a fight room first. */
		public String startGroup = "";
		/** Rogue: room type (fight, elite, boss) → the group its rooms come from. */
		public java.util.Map<String, String> roomGroups = new java.util.LinkedHashMap<>();
		/** Rogue: how often a door leads to each type (boss rooms come every bossEvery rooms instead). */
		public java.util.Map<String, Integer> typeWeights = new java.util.LinkedHashMap<>();
		/** Rogue: boons after rooms (and in shops) or not. */
		public boolean rogueBoons = true;
		/** Rogue: every n-th room is a boss room (0 = never). */
		public int bossEvery = 5;
		/** Rogue: rooms to clear for a win (0 = endless, until everyone is out). */
		public int maxDepth;
		/** Rogue: mob lines the spawn markers draw from, and the bosses of boss rooms (one at random). */
		public List<String> mobs = new ArrayList<>();
		public List<String> bosses = new ArrayList<>();
		/** Rogue: mobs in the first room, more per room after it, % more per extra player. */
		public int firstMobs = 3;
		public int perRoom = 1;
		public int perPlayer = 50;
		/** Rogue: % more health and damage per room after the first; elite rooms add eliteToughness % on top. */
		public int toughness = 10;
		public int eliteToughness = 50;
		/** In its pool (players can get it) or not. */
		public boolean enabled = true;
		/** Pads hand out a random dungeon from their own pool. */
		public String pool = "default";
		public int minParty = 1;
		public int maxParty = 4;
		/** 0 = out on the first death; else respawns at the last checkpoint until they run out. */
		public int lives;
		/** Out players watch as spectators until the run ends (else they're sent back at once). */
		public boolean outSpectate = true;
		/** Minutes; 0 = no limit. */
		public int timeLimit;
		/** Peaceful and neutral mobs in the dungeon (cows, villagers, iron golems, llamas, bees…) attack players too. */
		public boolean peacefulFight = true;
		/**
		 * "kit": their own things are put aside on entering and come back after (the kit is handed out instead);
		 * "own": they bring their own gear, and what they carry comes back after a death inside.
		 */
		public String gear = "kit";
		/** Kit items as the game writes them (taken from an admin's inventory with /dungeon kit). */
		public List<String> kit = new ArrayList<>();
		/** More kits: each player gets one of all the kits at random (the first is {@code kit}). */
		public List<List<String>> moreKits = new ArrayList<>();
		/** Kit mode: items found inside vanish afterwards (else they go to the player's inbox). */
		public boolean removePickups = true;
		/** "exit" (reaching an exit marker) or "boss" (every boss dead). */
		public String clearOn = "exit";
		/** Shown to the party; {player}, {party}, {dungeon}, {time}. Empty = the default line. */
		public String enterMessage = "";
		public String prepareMessage = "";
		public String clearedMessage = "";
		public String failedMessage = "";
		/** Run as the server once per player (without /), with the same placeholders. */
		public List<String> enterCommands = new ArrayList<>();
		public List<String> exitCommands = new ArrayList<>();
		/** Where it was built (so /dungeon save <name> can save it again after changes). */
		public String sourceDim;
		public int[] from;
		public int[] to;
		/** What the last save found: size, pieces and markers (filled by the mod). */
		public int sizeX;
		public int sizeY;
		public int sizeZ;
		public int pieces;
		public List<Marker> markers = new ArrayList<>();
		/** Its jigsaw blocks (the spots rooms go into); filled by the mod. */
		public List<Connector> connectors = new ArrayList<>();
		/** Levels of rooms inside rooms (1 = only the dungeon's own spots). */
		public int roomDepth = 4;
		/** Rooms in one copy at most. */
		public int maxRooms = 40;
		/** "x,y,z" of a spot → % chance it gets a room (overrides its group's chance). */
		public java.util.Map<String, Integer> spotChances = new java.util.LinkedHashMap<>();
		public long savedAt;
		public String savedBy;

		public boolean saved() {
			return pieces > 0 && sizeX > 0;
		}

		public boolean hasMarker(String type) {
			return markers.stream().anyMatch(m -> type.equals(m.type));
		}

		public boolean rogue() {
			return "rogue".equals(mode);
		}
	}

	/** A spot that starts a run when a player stands on it. */
	public static final class Pad {
		public String id = "";
		/** Starts a rogue run of its pool (else a dungeon of its pool). */
		public boolean rogue;
		public String pool = "default";
		public Games.Pos pos;
	}

	public static final class Settings {
		public boolean enabled = true;
		/** Most players in one run (a party). */
		public int maxParty = 4;
		/** Copies at the same time. */
		public int maxSlots = 8;
		/** Blocks between copies (whole chunks). */
		public int slotSpacing = 1024;
		/** Pieces pasted or wiped per tick (a piece is 16 × 16 columns). */
		public int piecesPerTick = 2;
		/** Seconds a player waits after a run before stepping on a pad again. */
		public int cooldown = 10;
		public List<Dungeon> dungeons = new ArrayList<>();
		public List<Pad> pads = new ArrayList<>();
		/** Optional waiting areas per pool: when every copy is in use, a party queues there and starts once one is free. */
		public java.util.Map<String, Games.Pos> waiting = new java.util.LinkedHashMap<>();
		/** The same for rogue run pools. */
		public java.util.Map<String, Games.Pos> rogueWaiting = new java.util.LinkedHashMap<>();
		public int waitRadius = 8;
		/** Rogue runs on (pads start them); dungeons have {@code enabled}. */
		public boolean rogueEnabled = true;
		public List<Room> rooms = new ArrayList<>();
		public List<Group> groups = new ArrayList<>();
		/** Rogue runs: coins, boons, shop and unlocks (the same for every rogue run). */
		public Rogue rogue = new Rogue();

		/** A room of either kind by id or name (names are unique across both, since their blocks are kept by name). */
		public Room room(String idOrName) {
			for (Room r : rooms) if (r.id.equals(idOrName) || r.name.equalsIgnoreCase(idOrName)) return r;
			return null;
		}

		/** A room of this kind (dungeon rooms or rogue rooms). */
		public Room room(String idOrName, boolean rogue) {
			Room r = room(idOrName);
			return r != null && r.rogue == rogue ? r : null;
		}

		public List<Room> rooms(boolean rogue) {
			return rooms.stream().filter(r -> r.rogue == rogue).toList();
		}

		public List<Room> roomsIn(String group, boolean rogue) {
			return rooms.stream().filter(r -> r.rogue == rogue && r.groups.contains(group)).toList();
		}

		public List<Group> groups(boolean rogue) {
			return groups.stream().filter(g -> g.rogue == rogue).toList();
		}

		/** The group's tunables (defaults when it has none yet). */
		public Group group(String name, boolean rogue) {
			for (Group g : groups) if (g.name.equals(name) && g.rogue == rogue) return g;
			Group g = new Group();
			g.name = name;
			g.rogue = rogue;
			return g;
		}

		/** A dungeon or rogue run by id or name (names are unique across both). */
		public Dungeon dungeon(String idOrName) {
			for (Dungeon d : dungeons) if (d.id.equals(idOrName) || d.name.equalsIgnoreCase(idOrName)) return d;
			return null;
		}

		/** A dungeon (rogue false) or a rogue run (rogue true) by id or name. */
		public Dungeon dungeon(String idOrName, boolean rogue) {
			Dungeon d = dungeon(idOrName);
			return d != null && d.rogue() == rogue ? d : null;
		}

		public List<Dungeon> dungeons(boolean rogue) {
			return dungeons.stream().filter(d -> d.rogue() == rogue).toList();
		}

		public List<Pad> pads(boolean rogue) {
			return pads.stream().filter(p -> p.rogue == rogue).toList();
		}

		/** The waiting areas of dungeon pools or of rogue run pools. */
		public java.util.Map<String, Games.Pos> waiting(boolean rogue) {
			return rogue ? rogueWaiting : waiting;
		}

		/** Every pool a dungeon (or rogue run) or a pad of that kind names. */
		public Set<String> pools(boolean rogue) {
			Set<String> out = new TreeSet<>();
			for (Dungeon d : dungeons) if (d.rogue() == rogue) out.add(d.pool);
			for (Pad p : pads) if (p.rogue == rogue) out.add(p.pool);
			return out;
		}

		private void fillRogue() {
			if (rogue == null) rogue = new Rogue();
			Rogue g = rogue;
			g.coinsPerKill = Math.max(0, Math.min(1000, g.coinsPerKill));
			g.eliteCoins = Math.max(1, Math.min(10, g.eliteCoins));
			g.bossCoins = Math.max(0, Math.min(10000, g.bossCoins));
			g.restHeal = Math.max(0, Math.min(100, g.restHeal));
			g.restFood = Math.max(0, Math.min(20, g.restFood));
			g.restSaturation = Math.max(0, Math.min(20, g.restSaturation));
			if (g.restEffects == null) g.restEffects = new ArrayList<>();
			g.restEffects.removeIf(x -> x == null || x.isBlank());
			if (g.restCommands == null) g.restCommands = new ArrayList<>();
			g.restCommands.removeIf(x -> x == null || x.isBlank());
			if (g.restMessage == null) g.restMessage = "";
			if (g.unlocksTitle == null || g.unlocksTitle.isBlank()) g.unlocksTitle = "Rogue unlocks";
			if (g.unlocksGreeting == null) g.unlocksGreeting = "";
			g.priceGrowth = Math.max(0, Math.min(100, g.priceGrowth));
			g.choices = Math.max(1, Math.min(6, g.choices));
			g.pickSeconds = Math.max(5, Math.min(300, g.pickSeconds));
			if (g.rewards == null) g.rewards = new ArrayList<>();
			g.rewards.removeIf(x -> x == null);
			for (RoomReward x : g.rewards) {
				// (Food from 1.107.0 couldn't fill past a full bar: it's armor now.)
				if ("food".equals(x.kind)) {
					x.kind = "armor";
					x.amount = 2;
				}
				if (!REWARD_KINDS.contains(x.kind)) x.kind = "boon";
				x.weight = Math.max(0, Math.min(100, x.weight));
				x.amount = Math.max(0, Math.min(10000, x.amount));
				x.perRoom = Math.max(0, Math.min(1000, x.perRoom));
			}
			if (g.shopName == null || g.shopName.isBlank()) g.shopName = "Merchant";
			if (g.shopSkin == null) g.shopSkin = "";
			if (g.boons == null) g.boons = new ArrayList<>();
			if (g.shop == null) g.shop = new ArrayList<>();
			if (g.unlocks == null) g.unlocks = new ArrayList<>();
			g.boons.removeIf(b -> b == null);
			g.shop.removeIf(b -> b == null);
			g.unlocks.removeIf(b -> b == null);
			Set<String> ids = new HashSet<>();
			for (Boon b : g.boons) {
				String want = b.id == null ? "" : b.id.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
				if (want.isEmpty()) want = b.name == null ? "" : b.name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
				if (want.isEmpty() || ids.contains(want)) want = "boon_" + UUID.randomUUID().toString().substring(0, 6);
				b.id = want.substring(0, Math.min(32, want.length()));
				ids.add(b.id);
				if (b.name == null || b.name.isBlank()) b.name = b.id;
				if (b.icon == null || b.icon.isBlank()) b.icon = "minecraft:nether_star";
				if (!BOON_KINDS.contains(b.kind)) b.kind = "attribute";
				if (b.target == null) b.target = "";
				if (!"percent".equals(b.op)) b.op = "add";
				b.amount = Double.isFinite(b.amount) ? Math.max(-1000, Math.min(1000, b.amount)) : 1;
				b.maxStacks = Math.max(0, Math.min(99, b.maxStacks));
				b.weight = Math.max(0, Math.min(100, b.weight));
				if (b.description == null) b.description = "";
			}
			ids.clear();
			for (ShopEntry e : g.shop) {
				if (e.id == null || e.id.isBlank() || !ids.add(e.id)) {
					e.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(e.id);
				}
				if (e.boon == null) e.boon = "";
				e.price = Math.max(0, Math.min(100000, e.price));
			}
			ids.clear();
			for (Unlock u : g.unlocks) {
				String want = u.id == null ? "" : u.id.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
				if (want.isEmpty() || ids.contains(want)) want = "unlock_" + UUID.randomUUID().toString().substring(0, 6);
				u.id = want.substring(0, Math.min(32, want.length()));
				ids.add(u.id);
				if (u.name == null || u.name.isBlank()) u.name = u.id;
				if (u.icon == null || u.icon.isBlank()) u.icon = "minecraft:experience_bottle";
				if (!UNLOCK_KINDS.contains(u.kind)) u.kind = "coins";
				if (u.boon == null) u.boon = "";
				if (u.description == null) u.description = "";
				u.amount = Math.max(0, Math.min(10000, u.amount));
				u.cost = Math.max(0, Math.min(1_000_000, u.cost));
				u.maxLevel = Math.max(1, Math.min("choice".equals(u.kind) ? 3 : 20, u.maxLevel));
			}
		}

		public Settings fill() {
			if (dungeons == null) dungeons = new ArrayList<>();
			if (pads == null) pads = new ArrayList<>();
			if (waiting == null) waiting = new java.util.LinkedHashMap<>();
			waiting.values().removeIf(p -> p == null || p.dim == null);
			if (rogueWaiting == null) rogueWaiting = new java.util.LinkedHashMap<>();
			rogueWaiting.values().removeIf(p -> p == null || p.dim == null);
			waitRadius = Math.max(2, Math.min(64, waitRadius));
			dungeons.removeIf(d -> d == null);
			pads.removeIf(p -> p == null || p.pos == null);
			maxParty = Math.max(1, Math.min(4, maxParty));
			maxSlots = Math.max(1, Math.min(64, maxSlots));
			slotSpacing = Math.max(256, Math.min(8192, slotSpacing / 16 * 16));
			piecesPerTick = Math.max(1, Math.min(16, piecesPerTick));
			cooldown = Math.max(0, Math.min(3600, cooldown));
			Set<String> ids = new HashSet<>();
			for (Dungeon d : dungeons) {
				if (d.id == null || d.id.isBlank() || !ids.add(d.id)) {
					d.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(d.id);
				}
				if (d.name == null) d.name = "";
				d.pool = pool(d.pool);
				d.minParty = Math.max(1, Math.min(maxParty, d.minParty));
				d.maxParty = Math.max(d.minParty, Math.min(maxParty, d.maxParty));
				d.lives = Math.max(0, Math.min(99, d.lives));
				d.timeLimit = Math.max(0, Math.min(600, d.timeLimit));
				if (!"boss".equals(d.clearOn)) d.clearOn = "exit";
				if (!"own".equals(d.gear)) d.gear = "kit";
				if (d.kit == null) d.kit = new ArrayList<>();
				if (d.moreKits == null) d.moreKits = new ArrayList<>();
				d.moreKits.removeIf(k -> k == null || k.isEmpty());
				// (Null strings from older files become empty.)
				if (d.enterMessage == null) d.enterMessage = "";
				if (d.prepareMessage == null) d.prepareMessage = "";
				if (d.clearedMessage == null) d.clearedMessage = "";
				if (d.failedMessage == null) d.failedMessage = "";
				if (d.enterCommands == null) d.enterCommands = new ArrayList<>();
				if (d.exitCommands == null) d.exitCommands = new ArrayList<>();
				if (d.markers == null) d.markers = new ArrayList<>();
				if (d.connectors == null) d.connectors = new ArrayList<>();
				if (d.spotChances == null) d.spotChances = new java.util.LinkedHashMap<>();
				d.spotChances.replaceAll((k, v) -> v == null ? 100 : Math.max(0, Math.min(100, v)));
				d.roomDepth = Math.max(1, Math.min(8, d.roomDepth));
				d.maxRooms = Math.max(1, Math.min(200, d.maxRooms));
				if (!"rogue".equals(d.mode)) d.mode = "dungeon";
				d.startGroup = d.startGroup == null || d.startGroup.isBlank() ? "" : pool(d.startGroup);
				java.util.Map<String, String> rg = new java.util.LinkedHashMap<>();
				java.util.Map<String, Integer> tw = new java.util.LinkedHashMap<>();
				for (String t : ROGUE_TYPES) {
					String g = d.roomGroups == null ? null : d.roomGroups.get(t);
					rg.put(t, g == null || g.isBlank() ? "" : pool(g));
					Integer w = d.typeWeights == null ? null : d.typeWeights.get(t);
					tw.put(t, Math.max(0, Math.min(100, w == null ? ("fight".equals(t) ? 70 : "elite".equals(t) ? 30 : "boss".equals(t) ? 0 : 10) : w)));
				}
				d.roomGroups = rg;
				d.typeWeights = tw;
				d.bossEvery = Math.max(0, Math.min(100, d.bossEvery));
				d.maxDepth = Math.max(0, Math.min(999, d.maxDepth));
				if (d.mobs == null) d.mobs = new ArrayList<>();
				if (d.bosses == null) d.bosses = new ArrayList<>();
				d.mobs.removeIf(x -> x == null || x.isBlank());
				d.bosses.removeIf(x -> x == null || x.isBlank());
				d.firstMobs = Math.max(0, Math.min(40, d.firstMobs));
				d.perRoom = Math.max(0, Math.min(20, d.perRoom));
				d.perPlayer = Math.max(0, Math.min(200, d.perPlayer));
				d.toughness = Math.max(0, Math.min(200, d.toughness));
				d.eliteToughness = Math.max(0, Math.min(500, d.eliteToughness));
			}
			if (rooms == null) rooms = new ArrayList<>();
			if (groups == null) groups = new ArrayList<>();
			rooms.removeIf(r -> r == null);
			ids.clear();
			for (Room r : rooms) {
				if (r.id == null || r.id.isBlank() || !ids.add(r.id)) {
					r.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(r.id);
				}
				if (r.name == null) r.name = "";
				List<String> gs = new ArrayList<>();
				if (r.groups != null) for (String g : r.groups) if (g != null && !g.isBlank() && !gs.contains(pool(g))) gs.add(pool(g));
				r.groups = gs;
				r.weight = Math.max(1, Math.min(100, r.weight));
				r.chance = Math.max(0, Math.min(100, r.chance));
				r.maxPerCopy = Math.max(0, Math.min(99, r.maxPerCopy));
				if (r.markers == null) r.markers = new ArrayList<>();
				if (r.connectors == null) r.connectors = new ArrayList<>();
			}
			// Every group a room or a spot of its kind names gets an entry; ones nothing names any more go.
			List<Group> kept = new ArrayList<>();
			for (boolean rogueKind : new boolean[] { false, true }) {
				Set<String> named = new TreeSet<>();
				for (Room r : rooms) {
					if (r.rogue != rogueKind) continue;
					named.addAll(r.groups);
					for (Connector c : r.connectors) if (c.spot()) named.add(c.pool);
				}
				for (Dungeon d : dungeons) {
					if (d.rogue() != rogueKind) continue;
					for (Connector c : d.connectors) if (c.spot()) named.add(c.pool);
					if (!d.rogue()) continue;
					if (!d.startGroup.isEmpty()) named.add(d.startGroup);
					for (String g : d.roomGroups.values()) if (!g.isEmpty()) named.add(g);
				}
				for (String n : named) {
					Group g = groups.stream().filter(x -> x != null && n.equals(x.name) && x.rogue == rogueKind).findFirst().orElse(null);
					if (g == null) {
						g = new Group();
						g.name = n;
						g.rogue = rogueKind;
					}
					g.fillChance = Math.max(0, Math.min(100, g.fillChance));
					g.minDepth = Math.max(0, Math.min(8, g.minDepth));
					kept.add(g);
				}
			}
			groups = kept;
			fillRogue();
			ids.clear();
			for (Pad p : pads) {
				if (p.id == null || p.id.isBlank() || !ids.add(p.id)) {
					p.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(p.id);
				}
				p.pool = pool(p.pool);
			}
			return this;
		}
	}

	/** A best clear: the time and who was in the party. */
	public static final class Best {
		/** Rogue runs: rooms cleared (more is better; the time breaks ties). */
		public int depth;
		public long millis;
		public List<String> names = new ArrayList<>();
		public long at;
		public int clears;
	}

	/** Best clears in the world folder: dungeon id → party size ("1".."4") → best. */
	public static final String SCORES = "nicecontrolcenter-dungeons-scores.json";

	public static java.util.Map<String, java.util.Map<String, Best>> readScores(Path file) {
		try {
			if (Files.exists(file)) {
				java.util.Map<String, java.util.Map<String, Best>> m = Json.GSON.fromJson(Files.readString(file),
						new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.Map<String, Best>>>() { }.getType());
				if (m != null) return m;
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return new java.util.LinkedHashMap<>();
	}

	/** Pool names are one lower-case word. */
	public static String pool(String name) {
		String p = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
		return p.isEmpty() ? "default" : p.substring(0, Math.min(32, p.length()));
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Dungeon d : s.dungeons) {
			if (!d.name.matches("[A-Za-z0-9_-]{1,32}")) return (d.rogue() ? "A rogue run" : "A dungeon") + " needs a one-word name (letters, digits, - and _).";
			if (!names.add(d.name.toLowerCase(Locale.ROOT))) return "Two dungeons or rogue runs are called \"" + d.name + "\" (the name is shared by both).";
		}
		names.clear();
		for (Room r : s.rooms) {
			if (!r.name.matches("[A-Za-z0-9_-]{1,32}")) return "A room needs a one-word name (letters, digits, - and _).";
			if (!names.add(r.name.toLowerCase(Locale.ROOT))) return "Two rooms are called \"" + r.name + "\" (dungeon and rogue rooms can't share a name).";
		}
		return null;
	}

	/** What the rogue file holds. */
	public static final class RogueFile {
		public boolean enabled = true;
		public List<Dungeon> runs = new ArrayList<>();
		public List<Room> rooms = new ArrayList<>();
		public List<Group> groups = new ArrayList<>();
		public List<Pad> pads = new ArrayList<>();
		public java.util.Map<String, Games.Pos> waiting = new java.util.LinkedHashMap<>();
		public Rogue rogue = new Rogue();
	}

	/** The dungeons file alone (rogue runs from before 1.110 still in it, as they were). */
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

	/**
	 * Dungeons and rogue runs together. Without a rogue file yet (before 1.110), the rogue runs, the rooms only they
	 * use, and the pads and waiting areas of pools with only rogue runs are taken out of the dungeons file: they're
	 * marked rogue and go to the rogue file on the next write.
	 */
	public static Settings readAll(Path file, Path rogueFile) {
		Settings s = read(file);
		RogueFile r = null;
		try {
			if (Files.exists(rogueFile)) r = Json.GSON.fromJson(Files.readString(rogueFile), RogueFile.class);
		} catch (IOException | RuntimeException e) {
			r = null;
		}
		if (r == null) {
			if (!Files.exists(rogueFile)) splitOld(s);
			return s.fill();
		}
		// Anything rogue left in the dungeons file is the rogue file's now.
		s.dungeons.removeIf(Dungeon::rogue);
		s.rooms.removeIf(x -> x.rogue);
		s.groups.removeIf(x -> x.rogue);
		s.pads.removeIf(x -> x.rogue);
		s.rogueEnabled = r.enabled;
		if (r.runs != null) for (Dungeon d : r.runs) if (d != null) {
			d.mode = "rogue";
			s.dungeons.add(d);
		}
		if (r.rooms != null) for (Room x : r.rooms) if (x != null) {
			x.rogue = true;
			s.rooms.add(x);
		}
		if (r.groups != null) for (Group x : r.groups) if (x != null) {
			x.rogue = true;
			s.groups.add(x);
		}
		if (r.pads != null) for (Pad x : r.pads) if (x != null) {
			x.rogue = true;
			s.pads.add(x);
		}
		s.rogueWaiting = r.waiting == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(r.waiting);
		if (r.rogue != null) s.rogue = r.rogue;
		// Never drop either file's entries (the next write would lose them): a rogue run named like a dungeon (only by
		// editing the files) gets "_rogue" added; anything else odd stays for the panel to point out.
		Set<String> taken = new HashSet<>();
		for (Dungeon d : s.dungeons) if (!d.rogue()) taken.add(d.name.toLowerCase(Locale.ROOT));
		for (Dungeon d : s.dungeons) {
			if (!d.rogue()) continue;
			String n = d.name == null ? "" : d.name;
			String base = n.substring(0, Math.min(24, n.length()));
			for (int i = 1; taken.contains(n.toLowerCase(Locale.ROOT)); i++) n = base + "_rogue" + (i == 1 ? "" : String.valueOf(i));
			d.name = n;
			taken.add(n.toLowerCase(Locale.ROOT));
		}
		return s.fill();
	}

	/** True once the rogue runs have a file of their own (else the next write makes it). */
	public static boolean hasRogueFile(Path rogueFile) {
		return Files.exists(rogueFile);
	}

	/** Before 1.110 rogue runs shared the dungeons' rooms, pads and pools: what only they use becomes theirs. */
	private static void splitOld(Settings s) {
		Set<String> rogueGroups = new HashSet<>();
		Set<String> rogueOnlyPools = new HashSet<>();
		for (Dungeon d : s.dungeons) {
			if (!d.rogue()) continue;
			if (d.startGroup != null && !d.startGroup.isBlank()) rogueGroups.add(d.startGroup);
			if (d.roomGroups != null) for (String g : d.roomGroups.values()) if (g != null && !g.isBlank()) rogueGroups.add(g);
			rogueOnlyPools.add(d.pool);
		}
		for (Dungeon d : s.dungeons) if (!d.rogue()) rogueOnlyPools.remove(d.pool);
		// Groups a dungeon's own spots use stay with the dungeons.
		Set<String> dungeonGroups = new HashSet<>();
		for (Dungeon d : s.dungeons) if (!d.rogue() && d.connectors != null) for (Connector c : d.connectors) if (c.spot()) dungeonGroups.add(c.pool);
		for (Room r : s.rooms) {
			if (r == null || r.groups == null || r.groups.isEmpty()) continue;
			boolean rogueRoom = r.groups.stream().anyMatch(rogueGroups::contains) && r.groups.stream().noneMatch(dungeonGroups::contains);
			if (rogueRoom) r.rogue = true;
		}
		for (Group g : s.groups) if (g != null && rogueGroups.contains(g.name) && !dungeonGroups.contains(g.name)) g.rogue = true;
		for (Pad p : s.pads) if (p != null && rogueOnlyPools.contains(pool(p.pool))) p.rogue = true;
		for (String pool : rogueOnlyPools) {
			Games.Pos w = s.waiting.remove(pool);
			if (w != null) s.rogueWaiting.put(pool, w);
		}
	}

	/** Writes the dungeons file and the rogue file (each with its own kind only). */
	public static void writeAll(Path file, Path rogueFile, Settings settings) throws IOException {
		Settings d = new Settings();
		d.enabled = settings.enabled;
		d.maxParty = settings.maxParty;
		d.maxSlots = settings.maxSlots;
		d.slotSpacing = settings.slotSpacing;
		d.piecesPerTick = settings.piecesPerTick;
		d.cooldown = settings.cooldown;
		d.waitRadius = settings.waitRadius;
		d.dungeons = new ArrayList<>(settings.dungeons(false));
		d.rooms = new ArrayList<>(settings.rooms(false));
		d.groups = new ArrayList<>(settings.groups(false));
		d.pads = new ArrayList<>(settings.pads(false));
		d.waiting = settings.waiting;
		d.rogueWaiting = null;
		d.rogue = null;
		RogueFile r = new RogueFile();
		r.enabled = settings.rogueEnabled;
		r.runs = new ArrayList<>(settings.dungeons(true));
		r.rooms = new ArrayList<>(settings.rooms(true));
		r.groups = new ArrayList<>(settings.groups(true));
		r.pads = new ArrayList<>(settings.pads(true));
		r.waiting = settings.rogueWaiting;
		r.rogue = settings.rogue;
		// The rogue file first: a dungeons file without rogue runs is only written once they're safe in theirs.
		writeJson(rogueFile, r);
		writeJson(file, d);
	}

	private static void writeJson(Path file, Object what) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(what), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
