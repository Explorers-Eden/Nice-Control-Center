package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Performance settings in the spirit of Paper and ServerCore, kept in config/nicecontrolcenter-performance.json:
 * dynamic distances while the server lags, entity activation range, merge radius, breeding caps, villager
 * brains, frozen chunks. Written by the panel, read by the mod when the file changes; Minecraft-free so
 * both compile it. Everything is off until switched on.
 */
public final class Performance {
	public static final String FILE = "nicecontrolcenter-performance.json";

	public static final class Dynamic {
		public boolean enabled = false;
		/** Steps down while the average tick takes longer than this (ms; 50 = the server can't keep up). */
		public int targetMspt = 40;
		/** Steps back up after a minute below this. */
		public int recoverMspt = 30;
		public int simulationMin = 5;
		public int viewMin = 6;
		/** Lowest mob cap in percent of vanilla. */
		public int mobCapMin = 50;
		/** Also lower the view distance (players notice it more), after simulation distance and mob caps. */
		public boolean view = true;
		/** Before the view distance: halve the activation ranges, then shrink how far players see mobs. */
		public boolean extraSteps = true;
	}

	public static final class Activation {
		public boolean enabled = false;
		/**
		 * How far from the nearest player mobs tick normally, in percent of the simulation distance (so never
		 * farther than it, and smaller while lag protection lowers it); farther away they only tick every
		 * {@link #every} ticks. At least 16 blocks.
		 */
		// 25 % of a simulation distance of 8 is 32 blocks, the old default.
		public int monstersPercent = 25;
		public int animalsPercent = 25;
		public int villagersPercent = 25;
		public int otherPercent = 15;
		public int every = 4;
	}

	public static final class Merge {
		public boolean enabled = false;
		/** Vanilla merges within 0.5 blocks. */
		public double items = 2.5;
		public double xp = 4;
	}

	public static final class Breeding {
		public boolean enabled = false;
		/** No breeding while this many of the same kind live within {@link #radius} blocks. */
		public int animals = 32;
		public int villagers = 16;
		public int radius = 16;
	}

	public static final class Villagers {
		/** Villagers that can't move (trading cells) think only every {@link #every} ticks. */
		public boolean lobotomize = false;
		public int every = 20;
	}

	/** How many mobs of a kind may live in one chunk: an id (minecraft:cow), a group (#animals) or * for any other kind. */
	public static final class MobRule {
		public String type;
		public int perChunk;

		public MobRule() {
		}

		public MobRule(String type, int perChunk) {
			this.type = type;
			this.perChunk = perChunk;
		}
	}

	/** The groups a rule can name, besides single mob ids. */
	public static final List<String> GROUPS = List.of("#animals", "#monsters", "#villagers", "#water", "#ambient");

	/** Applies the mob rules (and optionally a limit for dropped items) on its own, every few seconds. */
	public static final class AutoCaps {
		public boolean enabled = false;
		public int intervalSeconds = 60;
		public boolean items = false;
		public int itemsPerChunk = 64;
	}

	/** Paper's max-entity-collisions: a mob pushes at most this many others per tick. */
	public static final class Collisions {
		public boolean enabled = false;
		public int max = 8;
	}

	/** Junk items and stray arrows go sooner than vanilla's 5 minutes / 1 minute. */
	public static final class Despawn {
		public boolean enabled = false;
		public int itemSeconds = 60;
		public List<String> items = new ArrayList<>(List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack",
				"minecraft:dirt", "minecraft:gravel", "minecraft:sand", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
				"minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:arrow", "minecraft:spider_eye", "minecraft:kelp",
				"minecraft:bamboo", "minecraft:cactus"));
		/** Arrows stuck in the ground that players can't pick up (shot by mobs). */
		public int arrowSeconds = 10;
	}

	/** Paper's entity-per-chunk-save-limit: only this many of a kind are written when a chunk saves. */
	public static final class SaveLimits {
		public boolean enabled = false;
		public Map<String, Integer> limits = defaultSaveLimits();
	}

	public static Map<String, Integer> defaultSaveLimits() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("minecraft:arrow", 16);
		m.put("minecraft:spectral_arrow", 16);
		m.put("minecraft:trident", 4);
		m.put("minecraft:experience_orb", 16);
		m.put("minecraft:snowball", 8);
		m.put("minecraft:egg", 8);
		m.put("minecraft:ender_pearl", 8);
		m.put("minecraft:firework_rocket", 8);
		m.put("minecraft:small_fireball", 8);
		m.put("minecraft:fireball", 8);
		m.put("minecraft:wither_skull", 8);
		m.put("minecraft:item", 256);
		return m;
	}

	/** Mobs far from players re-plan their goals less often; at most so many new paths per tick. */
	public static final class Ai {
		public boolean enabled = false;
		/** Far means more than this percent of the simulation distance from every player (at least 16 blocks). */
		public int farPercent = 40;
		public int farGoalEvery = 4;
		public int pathBudget = 40;
		/** The path budget only while lag protection has lowered something. */
		public boolean whenLagging = true;
	}

	/** Hoppers that found nothing to move wait before trying again. */
	public static final class Hoppers {
		public boolean enabled = false;
		public int idleCooldown = 8;
	}

	/** Armor stands only tick while something happens to them (Paper's armor-stands-tick: false). */
	public static final class Decor {
		public boolean armorStands = false;
	}

	public static final class Chunk {
		public String dimension;
		public int x;
		public int z;

		public Chunk() {
		}

		public Chunk(String dimension, int x, int z) {
			this.dimension = dimension;
			this.x = x;
			this.z = z;
		}
	}

	public static final class Settings {
		public Dynamic dynamic = new Dynamic();
		public Activation activation = new Activation();
		public Merge merge = new Merge();
		public Breeding breeding = new Breeding();
		public Villagers villagers = new Villagers();
		/** Chunks whose entities and block entities don't tick. */
		public List<Chunk> frozen = new ArrayList<>();
		/** Mob limits per chunk, for the cleanup tool and the auto caps. */
		public List<MobRule> rules = new ArrayList<>();
		public AutoCaps autoCaps = new AutoCaps();
		public Collisions collisions = new Collisions();
		public Despawn despawn = new Despawn();
		public SaveLimits saveLimits = new SaveLimits();
		public Ai ai = new Ai();
		public Hoppers hoppers = new Hoppers();
		public Decor decor = new Decor();

		/** Missing parts (older file, partial JSON) get their defaults. */
		public Settings fill() {
			if (dynamic == null) dynamic = new Dynamic();
			if (activation == null) activation = new Activation();
			if (merge == null) merge = new Merge();
			if (breeding == null) breeding = new Breeding();
			if (villagers == null) villagers = new Villagers();
			if (frozen == null) frozen = new ArrayList<>();
			frozen.removeIf(c -> c == null || c.dimension == null || c.dimension.isBlank());
			if (rules == null) rules = new ArrayList<>();
			rules.removeIf(r -> r == null || r.type == null || r.type.isBlank());
			for (MobRule r : rules) r.type = id(r.type);
			if (autoCaps == null) autoCaps = new AutoCaps();
			if (collisions == null) collisions = new Collisions();
			if (despawn == null) despawn = new Despawn();
			if (despawn.items == null) despawn.items = new ArrayList<>();
			despawn.items.replaceAll(Performance::id);
			despawn.items.removeIf(String::isBlank);
			if (saveLimits == null) saveLimits = new SaveLimits();
			if (saveLimits.limits == null) saveLimits.limits = new LinkedHashMap<>();
			Map<String, Integer> limits = new LinkedHashMap<>();
			saveLimits.limits.forEach((k, v) -> { if (k != null && !k.isBlank() && v != null) limits.put(id(k), v); });
			saveLimits.limits = limits;
			if (ai == null) ai = new Ai();
			if (hoppers == null) hoppers = new Hoppers();
			if (decor == null) decor = new Decor();
			return this;
		}
	}

	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	private static final Pattern RULE_TYPE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+|#(animals|monsters|villagers|water|ambient)|\\*");

	private Performance() {
	}

	/** "Cow " or "cow" → "minecraft:cow"; groups and * stay. */
	public static String id(String raw) {
		String t = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
		if (t.isEmpty() || t.equals("*") || t.startsWith("#") || t.contains(":")) return t;
		return "minecraft:" + t.replace(' ', '_');
	}

	/** Returns an error, or null. */
	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Dynamic d = s.dynamic;
		if (d.targetMspt < 5 || d.targetMspt > 200) return "The target tick time must be between 5 and 200 ms.";
		if (d.recoverMspt < 1 || d.recoverMspt >= d.targetMspt) return "The recover tick time must be below the target.";
		if (d.simulationMin < 2 || d.simulationMin > 32) return "The lowest simulation distance must be between 2 and 32.";
		if (d.viewMin < 2 || d.viewMin > 32) return "The lowest view distance must be between 2 and 32.";
		if (d.mobCapMin < 10 || d.mobCapMin > 100) return "The lowest mob cap must be between 10 and 100 %.";
		Activation a = s.activation;
		for (int r : new int[] { a.monstersPercent, a.animalsPercent, a.villagersPercent, a.otherPercent }) {
			if (r < 10 || r > 100) return "Activation ranges must be between 10 and 100 % of the simulation distance.";
		}
		if (a.every < 2 || a.every > 40) return "Inactive entities tick every 2 to 40 ticks.";
		if (s.merge.items < 0.5 || s.merge.items > 8 || s.merge.xp < 0.5 || s.merge.xp > 8) return "Merge radii must be between 0.5 and 8 blocks.";
		Breeding b = s.breeding;
		if (b.animals < 2 || b.animals > 1000 || b.villagers < 2 || b.villagers > 1000) return "Breeding caps must be between 2 and 1000.";
		if (b.radius < 4 || b.radius > 128) return "The breeding radius must be between 4 and 128 blocks.";
		if (s.villagers.every < 2 || s.villagers.every > 100) return "Trapped villagers think every 2 to 100 ticks.";
		if (s.frozen.size() > 500) return "At most 500 frozen chunks.";
		if (s.rules.size() > 100) return "At most 100 mob limits.";
		for (MobRule r : s.rules) {
			if (!RULE_TYPE.matcher(r.type).matches()) return "\"" + r.type + "\" isn't a mob id, a group or *.";
			if (r.perChunk < 1 || r.perChunk > 500) return "Mob limits are 1 to 500 per chunk.";
		}
		AutoCaps c = s.autoCaps;
		if (c.intervalSeconds < 10 || c.intervalSeconds > 3600) return "Auto caps run every 10 to 3600 seconds.";
		if (c.itemsPerChunk < 8 || c.itemsPerChunk > 2000) return "The dropped item limit is 8 to 2000 per chunk.";
		if (s.collisions.max < 1 || s.collisions.max > 64) return "The collision cap is 1 to 64.";
		Despawn dsp = s.despawn;
		if (dsp.itemSeconds < 5 || dsp.itemSeconds > 300) return "Junk items despawn after 5 to 300 seconds.";
		if (dsp.arrowSeconds < 1 || dsp.arrowSeconds > 60) return "Stray arrows despawn after 1 to 60 seconds.";
		if (dsp.items.size() > 300) return "At most 300 junk items.";
		for (String id : dsp.items) if (!ID.matcher(id).matches()) return "\"" + id + "\" isn't an item id.";
		if (s.saveLimits.limits.size() > 100) return "At most 100 save limits.";
		for (Map.Entry<String, Integer> e : s.saveLimits.limits.entrySet()) {
			if (!ID.matcher(e.getKey()).matches()) return "\"" + e.getKey() + "\" isn't an entity id.";
			if (e.getValue() < 0 || e.getValue() > 5000) return "Save limits are 0 to 5000 per chunk.";
		}
		Ai ai = s.ai;
		if (ai.farPercent < 10 || ai.farPercent > 100) return "\"Far from players\" is 10 to 100 % of the simulation distance.";
		if (ai.farGoalEvery < 2 || ai.farGoalEvery > 20) return "Far mobs re-plan every 2 to 20 ticks.";
		if (ai.pathBudget < 5 || ai.pathBudget > 1000) return "The path budget is 5 to 1000 per tick.";
		if (s.hoppers.idleCooldown < 1 || s.hoppers.idleCooldown > 40) return "Idle hoppers wait 1 to 40 ticks.";
		return null;
	}

	/** The settings in the file; defaults when it's missing or unreadable. */
	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
