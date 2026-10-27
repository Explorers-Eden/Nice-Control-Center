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
 * Land claims (GriefPrevention style): rectangles from the bottom of the world to the sky, owned by a
 * player, with members in roles and switches for everyone else. Subclaims sit inside a claim and can
 * give members more rights there. Players pay for claims with claim blocks earned by playing.
 *
 * Settings live in config/nicecontrolcenter-claims.json (written by the panel), the claims themselves in
 * the world folder (nicecontrolcenter-claims.json, so backups carry them). Every change bumps a revision
 * so the panel can copy what changed into its database. Minecraft-free: the panel uses the same rules
 * to edit claims while the server is off.
 */
public final class Claims {
	public static final String FILE = "nicecontrolcenter-claims.json";
	/** Visual block setting: the concrete closest to the claim's colour. */
	public static final String CLAIM_COLOR = "claim_color";
	public static final String DATA_FILE = "nicecontrolcenter-claims.json";
	/** Tombstones kept for the panel's sync; older deletions mean a full copy. */
	private static final int TOMBSTONES = 2000;

	private Claims() {
	}

	// ---------------------------------------------------------------- settings

	public static final class Settings {
		/** Claims protect, the claim tool and /claim work. Off: nothing is protected, data is kept. */
		public boolean enabled = false;
		/** Players earn claim blocks even while claims are off, so they have some at launch. */
		public boolean accrueWhileDisabled = true;
		public String toolItem = "minecraft:golden_hoe";
		public int startingBlocks = 100;
		public int blocksPerHour = 100;
		public int maxAccruedBlocks = 80000;
		/** No blocks for minutes without moving, looking around or doing anything; 0 = always earn. */
		public int afkMinutes = 5;
		public int minWidth = 5;
		public int minArea = 100;
		/** Claims per player (subclaims not counted); 0 = no limit. */
		public int maxClaims = 0;
		/** Dimensions where players may claim. */
		public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
		/** What new claims allow everyone else. */
		public Flags defaultFlags = new Flags();
		/** Colours offered in the claim dialog (name → #RRGGBB). */
		public Map<String, String> colors = defaultColors();
		/**
		 * Fake blocks that show claim borders to one player (others see the world as it is). Block ids,
		 * or "claim_color" for the concrete closest to the claim's colour.
		 */
		public String visualCorner = "minecraft:glowstone";
		public String visualEdge = CLAIM_COLOR;
		public String visualSubCorner = "minecraft:iron_block";
		public String visualSubEdge = "minecraft:white_wool";
		/** The first corner of a new claim or subclaim, and a picked-up corner. */
		public String visualSelection = "minecraft:diamond_block";
		/** A marker every this many blocks along the edges. */
		public int visualSpacing = 10;
		/** Borders within this many blocks of the player are shown. */
		public int visualRange = 96;
		/** How long claim borders show after an action. */
		public int visualSeconds = 30;
		/** Claims of players away this many days are removed; 0 = never. */
		public int inactiveDays = 0;
		/** Players may only claim inside the permanent area (the soft border) where one is set. */
		public boolean onlyPermanentArea = true;
		/** Warping Wonders' waypoint hubs can only be placed inside the permanent area too (works with claims off). */
		public boolean hubsOnlyPermanentArea = true;
		/** Right-clicking your own hub opens a menu to rename it, make it private and trust players, with a button to the waypoint list. */
		public boolean hubsOwnerMenu = true;
		/** Dimensions where hubs can be placed (like {@link #dimensions} for claims); null until saved once with this list. */
		public List<String> hubsDimensions;
		/** Older setting: dimensions where hubs can't be placed. Only used while {@link #hubsDimensions} isn't set yet. */
		public List<String> hubsDeniedDimensions = new ArrayList<>();

		public Settings fill() {
			toolItem = itemId(toolItem, "minecraft:golden_hoe");
			visualCorner = visualId(visualCorner, "minecraft:glowstone");
			visualEdge = visualId(visualEdge, CLAIM_COLOR);
			visualSubCorner = visualId(visualSubCorner, "minecraft:iron_block");
			visualSubEdge = visualId(visualSubEdge, "minecraft:white_wool");
			visualSelection = visualId(visualSelection, "minecraft:diamond_block");
			if (dimensions == null) dimensions = new ArrayList<>();
			dimensions = dimensions.stream().filter(d -> d != null && !d.isBlank()).map(d -> d.strip().toLowerCase(Locale.ROOT))
					.map(d -> d.contains(":") ? d : "minecraft:" + d).distinct().toList();
			if (hubsDimensions != null) {
				hubsDimensions = hubsDimensions.stream().filter(d -> d != null && !d.isBlank()).map(d -> d.strip().toLowerCase(Locale.ROOT))
						.map(d -> d.contains(":") ? d : "minecraft:" + d).distinct().toList();
			}
			if (hubsDeniedDimensions == null) hubsDeniedDimensions = new ArrayList<>();
			hubsDeniedDimensions = hubsDeniedDimensions.stream().filter(d -> d != null && !d.isBlank()).map(d -> d.strip().toLowerCase(Locale.ROOT))
					.map(d -> d.contains(":") ? d : "minecraft:" + d).distinct().toList();
			if (defaultFlags == null) defaultFlags = new Flags();
			if (colors == null || colors.isEmpty()) colors = defaultColors();
			return this;
		}

		public boolean dimensionAllowed(String dim) {
			return dimensions.contains(dim);
		}

		/** Whether hubs may be placed in this dimension (until the list is saved once: everywhere the old setting didn't forbid). */
		public boolean hubsAllowed(String dim) {
			return hubsDimensions != null ? hubsDimensions.contains(dim) : !hubsDeniedDimensions.contains(dim);
		}
	}

	private static String itemId(String text, String fallback) {
		if (text == null || text.isBlank()) return fallback;
		String t = text.strip().toLowerCase(Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

	private static String visualId(String text, String fallback) {
		if (text != null && text.strip().equalsIgnoreCase(CLAIM_COLOR)) return CLAIM_COLOR;
		return itemId(text, fallback);
	}

	public static Map<String, String> defaultColors() {
		Map<String, String> c = new LinkedHashMap<>();
		c.put("Gold", "#F2B233");
		c.put("Red", "#E5484D");
		c.put("Orange", "#F76B15");
		c.put("Yellow", "#FFE629");
		c.put("Lime", "#7CD95C");
		c.put("Green", "#30A46C");
		c.put("Teal", "#12A594");
		c.put("Cyan", "#3FC9E8");
		c.put("Blue", "#3E63DD");
		c.put("Purple", "#8E4EC6");
		c.put("Pink", "#E93D82");
		c.put("Brown", "#A07553");
		c.put("White", "#F5F5F5");
		c.put("Gray", "#8B8D98");
		return c;
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (!s.toolItem.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "The tool item isn't an item id (like minecraft:golden_hoe).";
		if (s.startingBlocks < 0 || s.startingBlocks > 10_000_000) return "Starting blocks are 0 to 10,000,000.";
		if (s.blocksPerHour < 0 || s.blocksPerHour > 1_000_000) return "Blocks per hour are 0 to 1,000,000.";
		if (s.maxAccruedBlocks < 0 || s.maxAccruedBlocks > 100_000_000) return "The most blocks from playing are 0 to 100,000,000.";
		if (s.afkMinutes < 0 || s.afkMinutes > 1440) return "AFK minutes are 0 to 1440.";
		if (s.minWidth < 1 || s.minWidth > 1000) return "The smallest width is 1 to 1000 blocks.";
		if (s.minArea < 1 || s.minArea > 1_000_000) return "The smallest area is 1 to 1,000,000 blocks.";
		if (s.maxClaims < 0 || s.maxClaims > 1000) return "Claims per player are 0 (no limit) to 1000.";
		if (s.visualSeconds < 1 || s.visualSeconds > 600) return "Border display is 1 to 600 seconds.";
		if (s.visualSpacing < 2 || s.visualSpacing > 100) return "Border markers are every 2 to 100 blocks.";
		if (s.visualRange < 16 || s.visualRange > 256) return "Borders show within 16 to 256 blocks.";
		for (String v : new String[] { s.visualCorner, s.visualEdge, s.visualSubCorner, s.visualSubEdge, s.visualSelection }) {
			if (!v.equals(CLAIM_COLOR) && !v.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + v + "\" isn't a block id (or claim_color).";
		}
		if (s.inactiveDays < 0 || s.inactiveDays > 3650) return "Inactive days are 0 (never) to 3650.";
		for (String d : s.dimensions) if (!d.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "\"" + d + "\" isn't a dimension id.";
		if (s.colors.size() > 32) return "At most 32 colours.";
		for (Map.Entry<String, String> e : s.colors.entrySet()) {
			if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 24) return "Colour names are 1 to 24 characters.";
			if (parseColor(e.getValue()) < 0) return "\"" + e.getValue() + "\" isn't a #RRGGBB colour.";
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

	/** #RRGGBB (or RRGGBB) to an RGB int; -1 when it isn't one. */
	public static int parseColor(String text) {
		if (text == null) return -1;
		String t = text.strip();
		if (t.startsWith("#")) t = t.substring(1);
		if (!t.matches("[0-9a-fA-F]{6}")) return -1;
		return Integer.parseInt(t, 16);
	}

	public static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	// ---------------------------------------------------------------- claims

	/** What a member may do; each role includes the ones before it. */
	public enum Role {
		ACCESS("Visitor", "Doors, gates, buttons, levers, beds, trading"),
		CONTAINERS("Containers", "Also chests, furnaces and other storage, crops, and animals, villagers and other entities"),
		BUILD("Builder", "Also placing and breaking blocks, buckets, item frames"),
		MANAGE("Co-owner", "Also adding members, settings, resizing and subclaims"),
		OWNER("Owner", "Everything");

		public final String label;
		public final String description;

		Role(String label, String description) {
			this.label = label;
			this.description = description;
		}

		public boolean includes(Role needed) {
			return ordinal() >= needed.ordinal();
		}

		public static Role parse(String text) {
			if (text == null) return null;
			for (Role r : values()) if (r.name().equalsIgnoreCase(text.strip()) || r.label.equalsIgnoreCase(text.strip())) return r;
			return switch (text.strip().toLowerCase(Locale.ROOT)) {
				case "visitor", "access" -> ACCESS;
				case "container", "containers", "chests" -> CONTAINERS;
				case "builder", "build", "trust" -> BUILD;
				case "manager", "coowner", "co-owner", "manage" -> MANAGE;
				default -> null;
			};
		}
	}

	/** What everyone who isn't a member may do in the claim. */
	public static final class Flags {
		/** Doors, gates, buttons, levers, beds, trading. */
		public boolean access = false;
		/** Chests, furnaces and other storage, crops, and animals, villagers and other entities (interaction entities too). */
		public boolean containers = false;
		/** Players may hurt each other (members too). */
		public boolean pvp = false;
		/** Creepers, TNT and other explosions may break blocks. */
		public boolean explosions = false;
		/** Fire may spread and burn blocks. */
		public boolean fireSpread = false;
		/** Endermen, ravagers and other mobs may change blocks. */
		public boolean mobGriefing = false;

		public Flags copy() {
			Flags f = new Flags();
			f.access = access;
			f.containers = containers;
			f.pvp = pvp;
			f.explosions = explosions;
			f.fireSpread = fireSpread;
			f.mobGriefing = mobGriefing;
			return f;
		}

		/** Flag names with a label, in dialog order. */
		public static final String[][] LABELS = {
				{ "access", "Everyone may use doors, buttons and levers" },
				{ "containers", "Everyone may open chests and interact with entities" },
				{ "pvp", "Players may fight here" },
				{ "explosions", "Explosions break blocks" },
				{ "fireSpread", "Fire spreads and burns blocks" },
				{ "mobGriefing", "Mobs may change blocks (endermen…)" } };

		public boolean get(String name) {
			return switch (name) {
				case "access" -> access;
				case "containers" -> containers;
				case "pvp" -> pvp;
				case "explosions" -> explosions;
				case "fireSpread" -> fireSpread;
				case "mobGriefing" -> mobGriefing;
				default -> false;
			};
		}

		public void set(String name, boolean value) {
			switch (name) {
				case "access" -> access = value;
				case "containers" -> containers = value;
				case "pvp" -> pvp = value;
				case "explosions" -> explosions = value;
				case "fireSpread" -> fireSpread = value;
				case "mobGriefing" -> mobGriefing = value;
				default -> {
				}
			}
		}
	}

	public static final class Member {
		public String name;
		public Role role;

		public Member() {
		}

		public Member(String name, Role role) {
			this.name = name;
			this.role = role;
		}
	}

	/** Corners are inclusive block coordinates. */
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		public static Box of(int x1, int z1, int x2, int z2) {
			return new Box(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
		}

		public int width() {
			return maxX - minX + 1;
		}

		public int length() {
			return maxZ - minZ + 1;
		}

		public long area() {
			return (long) width() * length();
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		public boolean contains(Box o) {
			return o.minX >= minX && o.maxX <= maxX && o.minZ >= minZ && o.maxZ <= maxZ;
		}

		public boolean overlaps(Box o) {
			return o.minX <= maxX && o.maxX >= minX && o.minZ <= maxZ && o.maxZ >= minZ;
		}
	}

	/**
	 * A claim (or subclaim) for games, set by admins only: what players carry is put aside on entering and given
	 * back on leaving, a kit is handed out, and items picked up inside are removed or sent to the inbox.
	 */
	public static final class AdminOptions {
		/** Inventory, armour, off hand, XP and effects are put aside on entering and given back on leaving. */
		public boolean inventory = false;
		/** Items (as the game writes them, JSON) handed out on entering. */
		public List<String> kit = new ArrayList<>();
		/** Items picked up inside vanish on leaving (otherwise they go to the market inbox). */
		public boolean removePickups = false;
		/** Items picked up inside drop where the player dies (otherwise they go to the inbox). */
		public boolean dropPickupsOnDeath = false;
		/** No homes (Nice Actions), beds or anchors set a spawn inside, and the Nice Actions back teleport can't lead here. */
		public boolean noHomes = false;

		/** Does anything at all. */
		public boolean active() {
			return inventory || removePickups || noHomes || (kit != null && !kit.isEmpty());
		}

		public boolean blocksHomes() {
			return noHomes || inventory;
		}
	}

	public static final class Claim {
		public String id;
		/** The claim this one sits in; null for a claim of its own. */
		public String parent;
		/** Owner's UUID; null for a server claim made by an admin (costs no blocks). */
		public String owner;
		public String ownerName;
		public String dim;
		public int minX;
		public int minZ;
		public int maxX;
		public int maxZ;
		public String name = "";
		public int color = 0xF2B233;
		public Flags flags = new Flags();
		/** Game-claim options only admins set (for minigames): null when none. */
		public AdminOptions admin;
		/** UUID → member. */
		public Map<String, Member> members = new LinkedHashMap<>();
		public long created;
		public long updated;
		public long rev;
		/** Kept at least until then (ms) even when its owner stays away (an admin extended it); 0 = not extended. */
		public long keepUntil;

		public Box box() {
			return new Box(minX, minZ, maxX, maxZ);
		}

		void box(Box b) {
			minX = b.minX();
			minZ = b.minZ();
			maxX = b.maxX();
			maxZ = b.maxZ();
		}

		public boolean isSub() {
			return parent != null;
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		public String displayName() {
			if (name != null && !name.isBlank()) return name;
			return (ownerName == null ? "Server" : ownerName) + (isSub() ? "'s subclaim" : "'s claim");
		}

		Claim fill() {
			if (name == null) name = "";
			if (flags == null) flags = new Flags();
			if (members == null) members = new LinkedHashMap<>();
			members.values().removeIf(m -> m == null || m.role == null || m.role == Role.OWNER);
			return this;
		}
	}

	public static final class PlayerBlocks {
		public String name;
		/** Blocks earned by playing (and the starting blocks). */
		public int accrued;
		/** Blocks given or taken by admins. */
		public int bonus;
		/** Blocks bought in the market. */
		public int bought;
		/** Minutes played while earning (not AFK). */
		public long playMinutes;
		/** Part of a block earned but not yet whole. */
		public double partial;
		public long lastSeen;
		public long rev;
	}

	/** Everything in the world file. */
	public static final class Data {
		/** Tells a world from another one (a reset or a different world means the panel copies everything). */
		public String worldId = UUID.randomUUID().toString();
		public long rev;
		/** Deletions after this revision are all in {@link #tombstones}. */
		public long prunedRev;
		public Map<String, Claim> claims = new LinkedHashMap<>();
		public Map<String, PlayerBlocks> players = new LinkedHashMap<>();
		/** Claim id → revision it was removed at. */
		public Map<String, Long> tombstones = new LinkedHashMap<>();
		/** The world borders (soft border = permanent area), set by whoever loads the data; not saved. */
		public transient Map<String, Borders.Border> borders = Map.of();
		/** Who is in claim mode, with the item the claim tool took the place of (SNBT), if any; kept across crashes. */
		public Map<String, String> claimMode = new LinkedHashMap<>();

		public Data fill() {
			if (worldId == null || worldId.isBlank()) worldId = UUID.randomUUID().toString();
			if (claims == null) claims = new LinkedHashMap<>();
			if (players == null) players = new LinkedHashMap<>();
			if (tombstones == null) tombstones = new LinkedHashMap<>();
			if (claimMode == null) claimMode = new LinkedHashMap<>();
			claims.values().removeIf(c -> c == null || c.id == null || c.dim == null);
			claims.values().forEach(Claim::fill);
			// Subclaims whose claim is gone.
			claims.values().removeIf(c -> c.parent != null && !claims.containsKey(c.parent));
			return this;
		}

		long bump() {
			return ++rev;
		}

		void touch(Claim c, long now) {
			c.updated = now;
			c.rev = bump();
		}

		void touch(PlayerBlocks p) {
			p.rev = bump();
		}

		// ---- reading

		public List<Claim> subclaims(String parentId) {
			List<Claim> out = new ArrayList<>();
			for (Claim c : claims.values()) if (parentId.equals(c.parent)) out.add(c);
			return out;
		}

		public List<Claim> ownedBy(String uuid) {
			List<Claim> out = new ArrayList<>();
			for (Claim c : claims.values()) if (c.parent == null && uuid.equals(c.owner)) out.add(c);
			return out;
		}

		public long used(String uuid) {
			long sum = 0;
			for (Claim c : claims.values()) if (c.parent == null && uuid.equals(c.owner)) sum += c.box().area();
			return sum;
		}

		public PlayerBlocks player(String uuid) {
			return players.get(uuid);
		}

		public long available(String uuid) {
			PlayerBlocks p = players.get(uuid);
			long total = p == null ? 0 : (long) p.accrued + p.bonus + p.bought;
			return total - used(uuid);
		}

		/** The player's role in the claim (subclaims: the higher of their role there and in the claim around). */
		public Role roleOf(Claim c, String uuid) {
			if (uuid == null) return null;
			Claim top = c.parent == null ? c : claims.get(c.parent);
			if (top != null && uuid.equals(top.owner)) return Role.OWNER;
			Role best = null;
			Member own = c.members.get(uuid);
			if (own != null) best = own.role;
			if (c.parent != null && top != null) {
				Member outer = top.members.get(uuid);
				if (outer != null && (best == null || outer.role.includes(best))) best = outer.role;
			}
			return best;
		}

		/**
		 * When the claim goes because its owner stays away (to a co-owner who still plays, or removed): the
		 * owner's last visit plus the inactive days, or {@link Claim#keepUntil} when that's later. 0 = never:
		 * no limit set, a server claim, a subclaim (goes with its claim) or an owner never seen.
		 */
		public long expiresAt(Claim c, Settings s) {
			if (s.inactiveDays <= 0 || c.parent != null || c.owner == null) return 0;
			PlayerBlocks p = players.get(c.owner);
			if (p == null || p.lastSeen <= 0) return 0;
			return Math.max(p.lastSeen + s.inactiveDays * 86_400_000L, c.keepUntil);
		}

		/** May the player do something that needs this role here (members, or everyone through the switches)? */
		public boolean allows(Claim c, String uuid, Role needed) {
			Role r = roleOf(c, uuid);
			if (r != null && r.includes(needed)) return true;
			if (needed == Role.ACCESS) return c.flags.access || c.flags.containers;
			if (needed == Role.CONTAINERS) return c.flags.containers;
			return false;
		}

		// ---- changing; each returns an error for the player, or null

		/** Players get their starting blocks the first time they're seen. */
		public PlayerBlocks ensurePlayer(String uuid, String name, Settings s) {
			PlayerBlocks p = players.get(uuid);
			if (p == null) {
				p = new PlayerBlocks();
				p.name = name;
				p.accrued = s.startingBlocks;
				players.put(uuid, p);
				touch(p);
			} else if (name != null && !name.equals(p.name)) {
				p.name = name;
				touch(p);
			}
			return p;
		}

		/** One minute of play: blocksPerHour / 60, up to the limit. */
		public void accrue(String uuid, String name, Settings s, long now) {
			accrue(uuid, name, s, now, 1.0);
		}

		/** factor: a claim-block boost reward (2 = twice as many blocks this minute). */
		public void accrue(String uuid, String name, Settings s, long now, double factor) {
			PlayerBlocks p = ensurePlayer(uuid, name, s);
			p.playMinutes++;
			p.lastSeen = now;
			if (p.accrued < s.maxAccruedBlocks) {
				p.partial += s.blocksPerHour / 60.0 * factor;
				int whole = (int) p.partial;
				p.partial -= whole;
				p.accrued = Math.min(s.maxAccruedBlocks, p.accrued + whole);
			}
			touch(p);
		}

		public void seen(String uuid, String name, Settings s, long now) {
			PlayerBlocks p = ensurePlayer(uuid, name, s);
			p.lastSeen = now;
			touch(p);
		}

		public void setBonus(String uuid, String name, int bonus) {
			PlayerBlocks p = players.computeIfAbsent(uuid, k -> new PlayerBlocks());
			if (p.name == null) p.name = name;
			p.bonus = bonus;
			touch(p);
		}

		public void setAccrued(String uuid, String name, int accrued) {
			PlayerBlocks p = players.computeIfAbsent(uuid, k -> new PlayerBlocks());
			if (p.name == null) p.name = name;
			p.accrued = Math.max(0, accrued);
			touch(p);
		}

		/** How many more blocks the player may buy in the market, with at most max in all (0 = no limit, then Integer.MAX_VALUE). */
		public int blocksLeft(String uuid, int max) {
			if (max <= 0) return Integer.MAX_VALUE;
			PlayerBlocks p = players.get(uuid);
			int bought = p == null ? 0 : p.bought;
			return Math.max(0, max - bought);
		}

		/** Adds bought blocks (the currency is already taken). */
		public void buy(String uuid, String name, int blocks, Settings s) {
			PlayerBlocks p = ensurePlayer(uuid, name, s);
			p.bought += blocks;
			touch(p);
		}

		/** Is the whole box inside the dimension's soft border (true when it has none)? */
		public boolean insidePermanent(String dim, Box box) {
			Borders.Border b = borders == null ? null : borders.get(dim);
			if (b == null || b.soft <= 0) return true;
			// Block edges: the far side of the last block counts.
			return b.inside(box.minX(), box.minZ(), b.soft) && b.inside(box.maxX() + 1, box.minZ(), b.soft)
					&& b.inside(box.minX(), box.maxZ() + 1, b.soft) && b.inside(box.maxX() + 1, box.maxZ() + 1, b.soft);
		}

		/** Checks a new place for a claim (or a moved one: {@code self} is left out of the overlap check). */
		public String check(String dim, Box box, String parentId, String ownerUuid, Claim self, Settings s, boolean admin) {
			if (parentId == null) {
				if (!admin && !s.dimensionAllowed(dim)) return "You can't claim land in this dimension.";
				if (!admin && s.onlyPermanentArea && !insidePermanent(dim, box)) return "Part of this claim is too far out: land out there is reset from time to time.";
				if (!admin && (box.width() < s.minWidth || box.length() < s.minWidth))
					return "A claim must be at least " + s.minWidth + " blocks wide (this one is " + box.width() + " × " + box.length() + ").";
				if (!admin && box.area() < s.minArea) return "A claim must cover at least " + s.minArea + " blocks (this one has " + box.area() + ").";
				for (Claim o : claims.values()) {
					if (o == self || o.parent != null || !o.dim.equals(dim)) continue;
					if (o.box().overlaps(box)) return "That overlaps " + o.displayName() + ".";
				}
				if (self != null) {
					for (Claim sub : subclaims(self.id)) if (!box.contains(sub.box())) return "The claim must still hold its subclaim " + sub.displayName() + ".";
				}
				if (ownerUuid != null && !admin) {
					long cost = box.area() - (self == null ? 0 : self.box().area());
					long left = available(ownerUuid);
					if (cost > left) return "That needs " + cost + " more claim blocks; you have " + Math.max(0, left) + ".";
				}
			} else {
				Claim parent = claims.get(parentId);
				if (parent == null) return "The claim around it is gone.";
				if (!parent.dim.equals(dim) || !parent.box().contains(box)) return "A subclaim must be inside its claim.";
				for (Claim o : subclaims(parentId)) {
					if (o != self && o.box().overlaps(box)) return "That overlaps the subclaim " + o.displayName() + ".";
				}
			}
			return null;
		}

		public static final class Result {
			public final Claim claim;
			public final String error;

			Result(Claim claim, String error) {
				this.claim = claim;
				this.error = error;
			}

			public boolean ok() {
				return error == null;
			}
		}

		public Result create(String ownerUuid, String ownerName, String dim, Box box, String parentId, Settings s, boolean admin, long now) {
			if (parentId == null && ownerUuid != null && !admin && s.maxClaims > 0 && ownedBy(ownerUuid).size() >= s.maxClaims)
				return new Result(null, "You already have " + s.maxClaims + (s.maxClaims == 1 ? " claim." : " claims."));
			String err = check(dim, box, parentId, ownerUuid, null, s, admin);
			if (err != null) return new Result(null, err);
			Claim c = new Claim();
			c.id = UUID.randomUUID().toString();
			c.parent = parentId;
			Claim parent = parentId == null ? null : claims.get(parentId);
			c.owner = parent != null ? parent.owner : ownerUuid;
			c.ownerName = parent != null ? parent.ownerName : ownerName;
			c.dim = dim;
			c.box(box);
			c.flags = parent != null ? parent.flags.copy() : s.defaultFlags.copy();
			c.color = parent != null ? parent.color : firstColor(s);
			c.created = now;
			claims.put(c.id, c);
			tombstones.remove(c.id);
			touch(c, now);
			return new Result(c, null);
		}

		public String resize(Claim c, Box box, Settings s, boolean admin, long now) {
			String err = check(c.dim, box, c.parent, c.owner, c, s, admin);
			if (err != null) return err;
			c.box(box);
			touch(c, now);
			return null;
		}

		/** Removes the claim and its subclaims. */
		public void delete(Claim c) {
			for (Claim sub : subclaims(c.id)) delete(sub);
			claims.remove(c.id);
			tombstones.put(c.id, bump());
			if (tombstones.size() > TOMBSTONES) {
				var it = tombstones.entrySet().iterator();
				while (tombstones.size() > TOMBSTONES && it.hasNext()) {
					prunedRev = Math.max(prunedRev, it.next().getValue());
					it.remove();
				}
			}
		}

		public String rename(Claim c, String name, long now) {
			String n = name == null ? "" : name.strip();
			if (n.length() > 32) return "Names are up to 32 characters.";
			c.name = n;
			touch(c, now);
			return null;
		}

		public void recolor(Claim c, int rgb, long now) {
			c.color = rgb & 0xFFFFFF;
			touch(c, now);
		}

		public void setFlags(Claim c, Flags flags, long now) {
			c.flags = flags.copy();
			touch(c, now);
		}

		public String setMember(Claim c, String uuid, String name, Role role, long now) {
			if (role == null || role == Role.OWNER) return "Pick a role.";
			if (uuid.equals(c.owner)) return name + " owns this claim.";
			if (c.members.size() >= 200 && !c.members.containsKey(uuid)) return "A claim can have up to 200 members.";
			c.members.put(uuid, new Member(name, role));
			touch(c, now);
			return null;
		}

		public boolean removeMember(Claim c, String uuid, long now) {
			if (c.members.remove(uuid) == null) return false;
			touch(c, now);
			return true;
		}

		/** Gives the claim (and its subclaims) to someone else; their blocks pay for it unless an admin does it. */
		public String transfer(Claim c, String uuid, String name, Settings s, boolean admin, long now) {
			if (c.parent != null) return "Subclaims belong to the claim around them.";
			if (uuid != null && !admin && available(uuid) < c.box().area()) return name + " doesn't have enough claim blocks for it.";
			c.owner = uuid;
			c.ownerName = name;
			if (uuid != null) c.members.remove(uuid);
			touch(c, now);
			for (Claim sub : subclaims(c.id)) {
				sub.owner = uuid;
				sub.ownerName = name;
				touch(sub, now);
			}
			return null;
		}

		/** Changes since a revision, for the panel's copy. Everything when {@code since} is too old to tell deletions. */
		public Map<String, Object> changes(long since) {
			boolean full = since <= 0 || since < prunedRev || since > rev;
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("worldId", worldId);
			out.put("rev", rev);
			out.put("full", full);
			List<Claim> changed = new ArrayList<>();
			for (Claim c : claims.values()) if (full || c.rev > since) changed.add(c);
			out.put("claims", changed);
			List<String> removed = new ArrayList<>();
			if (!full) tombstones.forEach((id, r) -> {
				if (r > since) removed.add(id);
			});
			out.put("removed", removed);
			Map<String, PlayerBlocks> ps = new LinkedHashMap<>();
			players.forEach((id, p) -> {
				if (full || p.rev > since) ps.put(id, p);
			});
			out.put("players", ps);
			return out;
		}
	}

	// ---------------------------------------------------------------- panel actions

	/**
	 * An edit from the panel (admins: no claim block or size limits), as JSON:
	 * {"action": "create|resize|delete|update|member_set|member_remove|transfer|blocks", ...}.
	 * Returns {"ok": true, "claim": …} or {"error": "…"}. The mod runs it live, the panel on the world
	 * file while the server is off.
	 */
	public static Map<String, Object> apply(Data data, Settings settings, com.google.gson.JsonObject a, long now) {
		Map<String, Object> out = new LinkedHashMap<>();
		String action = str(a, "action");
		if (action.equals("blocks")) {
			String uuid = str(a, "uuid");
			if (!isUuid(uuid)) return error("Unknown player.");
			String name = str(a, "name");
			if (a.has("bonus")) data.setBonus(uuid, name.isEmpty() ? null : name, a.get("bonus").getAsInt());
			if (a.has("accrued")) data.setAccrued(uuid, name.isEmpty() ? null : name, a.get("accrued").getAsInt());
			if (a.has("bought")) {
				PlayerBlocks p = data.players.computeIfAbsent(uuid, k -> new PlayerBlocks());
				if (p.name == null && !name.isEmpty()) p.name = name;
				p.bought = Math.max(0, a.get("bought").getAsInt());
				data.touch(p);
			}
			out.put("ok", true);
			out.put("player", data.player(uuid));
			return out;
		}
		if (action.equals("create")) {
			String owner = str(a, "owner");
			String parent = str(a, "parent");
			if (!owner.isEmpty() && !isUuid(owner)) return error("Unknown owner.");
			Box box = Box.of(a.get("minX").getAsInt(), a.get("minZ").getAsInt(), a.get("maxX").getAsInt(), a.get("maxZ").getAsInt());
			Data.Result r = data.create(owner.isEmpty() ? null : owner, owner.isEmpty() ? null : str(a, "ownerName"), str(a, "dim"), box,
					parent.isEmpty() ? null : parent, settings, true, now);
			if (!r.ok()) return error(r.error);
			if (a.has("name")) data.rename(r.claim, str(a, "name"), now);
			out.put("ok", true);
			out.put("claim", r.claim);
			return out;
		}
		Claim c = data.claims.get(str(a, "claim"));
		if (c == null) return error("That claim doesn't exist any more.");
		switch (action) {
			case "resize" -> {
				String err = data.resize(c, Box.of(a.get("minX").getAsInt(), a.get("minZ").getAsInt(), a.get("maxX").getAsInt(), a.get("maxZ").getAsInt()), settings, true, now);
				if (err != null) return error(err);
			}
			case "delete" -> data.delete(c);
			case "update" -> {
				if (a.has("name")) {
					String err = data.rename(c, str(a, "name"), now);
					if (err != null) return error(err);
				}
				if (a.has("color")) {
					int rgb = parseColor(str(a, "color"));
					if (rgb < 0) return error("That isn't a #RRGGBB colour.");
					data.recolor(c, rgb, now);
				}
				if (a.has("flags") && a.get("flags").isJsonObject()) {
					Flags f = c.flags.copy();
					for (String[] flag : Flags.LABELS) {
						var v = a.getAsJsonObject("flags").get(flag[0]);
						if (v != null && v.isJsonPrimitive()) f.set(flag[0], v.getAsBoolean());
					}
					data.setFlags(c, f, now);
				}
			}
			case "admin" -> {
				// Game-claim options (admins only; the panel and /claim admin).
				if (c.admin == null) c.admin = new AdminOptions();
				for (String k : List.of("inventory", "removePickups", "dropPickupsOnDeath", "noHomes")) {
					var v = a.get(k);
					if (v == null || !v.isJsonPrimitive()) continue;
					boolean on = v.getAsBoolean();
					switch (k) {
						case "inventory" -> c.admin.inventory = on;
						case "removePickups" -> c.admin.removePickups = on;
						case "dropPickupsOnDeath" -> c.admin.dropPickupsOnDeath = on;
						default -> c.admin.noHomes = on;
					}
				}
				if (a.has("clearKit") && a.get("clearKit").getAsBoolean()) c.admin.kit = new ArrayList<>();
				if (!c.admin.active() && !c.admin.dropPickupsOnDeath) c.admin = null;
				data.touch(c, now);
			}
			case "member_set" -> {
				String uuid = str(a, "uuid");
				if (!isUuid(uuid)) return error("Unknown player.");
				String err = data.setMember(c, uuid, str(a, "name"), Role.parse(str(a, "role")), now);
				if (err != null) return error(err);
			}
			case "member_remove" -> data.removeMember(c, str(a, "uuid"), now);
			case "keep" -> {
				// Extends (or with 0 clears) how long the claim stays while its owner is away.
				if (c.parent != null) return error("Subclaims go with their claim.");
				if (c.owner == null) return error("Server claims never expire.");
				long until = a.has("until") ? a.get("until").getAsLong() : 0;
				c.keepUntil = Math.max(0, until);
				data.touch(c, now);
			}
			case "transfer" -> {
				String owner = str(a, "owner");
				if (!owner.isEmpty() && !isUuid(owner)) return error("Unknown owner.");
				String err = data.transfer(c, owner.isEmpty() ? null : owner, owner.isEmpty() ? null : str(a, "ownerName"), settings, true, now);
				if (err != null) return error(err);
			}
			default -> {
				return error("Unknown action.");
			}
		}
		out.put("ok", true);
		if (!action.equals("delete")) out.put("claim", c);
		return out;
	}

	private static Map<String, Object> error(String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("error", text);
		return m;
	}

	private static String str(com.google.gson.JsonObject o, String key) {
		var v = o.get(key);
		return v == null || v.isJsonNull() ? "" : v.getAsString().strip();
	}

	private static boolean isUuid(String s) {
		try {
			UUID.fromString(s);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public static int firstColor(Settings s) {
		for (String v : s.colors.values()) {
			int c = parseColor(v);
			if (c >= 0) return c;
		}
		return 0xF2B233;
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
