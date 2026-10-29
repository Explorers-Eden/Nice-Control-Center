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
	/** The void dimension the copies are made in (shipped with the mod). */
	public static final String DIMENSION = "nicecontrolcenter:dungeons";
	/** Pieces are 16 × 16 columns (one chunk wide), as high as the box (its two corners). */
	public static final int PIECE = 16;
	/** Markers: data-mode structure blocks in the build, with one of these as their text. */
	public static final List<String> MARKERS = List.of("start", "exit", "checkpoint", "mob", "boss");

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
		/** %: the chance a spot of this group gets a room at all (else it stays closed). */
		public int fillChance = 100;
		/** Rooms of this group come only this many levels in (0 = right away; 1 = only inside another room…). */
		public int minDepth;
	}

	public static final class Dungeon {
		public String id = "";
		public String name = "";
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
		/**
		 * "kit": their own things are put aside on entering and come back after (the kit is handed out instead);
		 * "own": they bring their own gear, and what they carry comes back after a death inside.
		 */
		public String gear = "kit";
		/** Kit items as the game writes them (taken from an admin's inventory with /dungeon kit). */
		public List<String> kit = new ArrayList<>();
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
	}

	/** A spot that starts a run when a player stands on it. */
	public static final class Pad {
		public String id = "";
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
		public int waitRadius = 8;
		public List<Room> rooms = new ArrayList<>();
		public List<Group> groups = new ArrayList<>();

		public Room room(String idOrName) {
			for (Room r : rooms) if (r.id.equals(idOrName) || r.name.equalsIgnoreCase(idOrName)) return r;
			return null;
		}

		public List<Room> roomsIn(String group) {
			return rooms.stream().filter(r -> r.groups.contains(group)).toList();
		}

		/** The group's tunables (defaults when it has none yet). */
		public Group group(String name) {
			for (Group g : groups) if (g.name.equals(name)) return g;
			Group g = new Group();
			g.name = name;
			return g;
		}

		public Dungeon dungeon(String idOrName) {
			for (Dungeon d : dungeons) if (d.id.equals(idOrName) || d.name.equalsIgnoreCase(idOrName)) return d;
			return null;
		}

		/** Every pool a dungeon or a pad names. */
		public Set<String> pools() {
			Set<String> out = new TreeSet<>();
			for (Dungeon d : dungeons) out.add(d.pool);
			for (Pad p : pads) out.add(p.pool);
			return out;
		}

		public Settings fill() {
			if (dungeons == null) dungeons = new ArrayList<>();
			if (pads == null) pads = new ArrayList<>();
			if (waiting == null) waiting = new java.util.LinkedHashMap<>();
			waiting.values().removeIf(p -> p == null || p.dim == null);
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
			// Every group a room or a spot names gets an entry; ones nothing names any more go.
			Set<String> named = new TreeSet<>();
			for (Room r : rooms) {
				named.addAll(r.groups);
				for (Connector c : r.connectors) if (c.spot()) named.add(c.pool);
			}
			for (Dungeon d : dungeons) for (Connector c : d.connectors) if (c.spot()) named.add(c.pool);
			List<Group> kept = new ArrayList<>();
			for (String n : named) {
				Group g = groups.stream().filter(x -> x != null && n.equals(x.name)).findFirst().orElse(null);
				if (g == null) {
					g = new Group();
					g.name = n;
				}
				g.fillChance = Math.max(0, Math.min(100, g.fillChance));
				g.minDepth = Math.max(0, Math.min(8, g.minDepth));
				kept.add(g);
			}
			groups = kept;
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
			if (!d.name.matches("[A-Za-z0-9_-]{1,32}")) return "A dungeon needs a one-word name (letters, digits, - and _).";
			if (!names.add(d.name.toLowerCase(Locale.ROOT))) return "Two dungeons are called \"" + d.name + "\".";
		}
		names.clear();
		for (Room r : s.rooms) {
			if (!r.name.matches("[A-Za-z0-9_-]{1,32}")) return "A room needs a one-word name (letters, digits, - and _).";
			if (!names.add(r.name.toLowerCase(Locale.ROOT))) return "Two rooms are called \"" + r.name + "\".";
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
}
