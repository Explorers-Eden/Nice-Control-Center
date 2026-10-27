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
 * Mini-game arenas (templates: infinite parkour, race course, dropper, maze, wave fight), kept in
 * config/nicecontrolcenter-games.json: made with /game in game, changed in the panel's Games page. Scores are kept
 * by the mod in the world folder (nicecontrolcenter-games-scores.json). Minecraft-free so both compile it.
 */
public final class Games {
	public static final String FILE = "nicecontrolcenter-games.json";
	public static final String SCORES = "nicecontrolcenter-games-scores.json";
	public static final List<String> TYPES = List.of("parkour", "race", "dropper", "maze", "waves");

	/** A spot in a dimension. */
	public static final class Pos {
		public String dim = "";
		public double x;
		public double y;
		public double z;
		public float yaw;

		public Pos() {
		}

		public Pos(String dim, double x, double y, double z, float yaw) {
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
		}
	}

	/** A sphere to pass through (race checkpoints, finish areas). */
	public static final class Point {
		public double x;
		public double y;
		public double z;
		public double radius = 3;

		public Point() {
		}

		public Point(double x, double y, double z, double radius) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
		}
	}

	public static final class Arena {
		public String id = "";
		public String name = "";
		/** parkour, race, dropper or maze. */
		public String type = "parkour";
		public boolean enabled = true;
		/** The start pad: stepping on it starts a run (and where /game join brings players). */
		public Pos start;
		/** Parkour: blocks the jumps are made of. */
		public List<String> palette = new ArrayList<>(List.of("minecraft:white_concrete", "minecraft:light_blue_concrete", "minecraft:lime_concrete",
				"minecraft:yellow_concrete", "minecraft:orange_concrete", "minecraft:pink_concrete", "minecraft:magenta_concrete"));
		/** Parkour: how many players can run at once (each in their own lane), and the gap between lanes. */
		public int lanes = 8;
		public int laneSpacing = 32;
		/** Race: run, boat or elytra; the checkpoints in order and the finish. */
		public String mode = "run";
		public List<Point> checkpoints = new ArrayList<>();
		/** Race and dropper: where a run ends. */
		public Point finish;
		/** Dropper: where players drop from. */
		public Pos top;
		/** Maze: the corner it's built from, cells per side, the blocks, and when it's made new ("run" or "daily"). */
		public Pos corner;
		public int size = 12;
		public String wall = "minecraft:stone_bricks";
		public String floor = "minecraft:smooth_stone";
		public String regenerate = "daily";
		/** Maze: wall height in blocks. */
		public int wallHeight = 3;
		/** Parkour: jumps shown ahead, and the longest gap (blocks) it gets to. */
		public int ahead = 3;
		public int maxGap = 4;
		/** Race: seconds before a run is over (0 = no limit). */
		public int timeLimit;
		/** Dropper: tries before a run is over (0 = as many as it takes). */
		public int maxTries;
		/** Seconds before a player can start this arena again. */
		public int cooldown = 3;
		/** Shown on start and when a run counts; {player}, {game}, {score}. Empty = the default line. */
		public String startMessage = "";
		public String finishMessage = "";
		/** Run as the server for the player (without /) on start and when a run counts. */
		public List<String> startCommands = new ArrayList<>();
		public List<String> finishCommands = new ArrayList<>();

		// Waves: a party fights waves of mobs in a box built in the world.
		/** Where players appear (and come back after dying); the fight box (two corners, its height included). */
		public Pos spawn;
		public Pos areaMin;
		public Pos areaMax;
		/** Where mobs come from (picked at random). */
		public List<Pos> mobSpawns = new ArrayList<>();
		/** Optional: where dead party members wait for the next wave and other parties queue up; its radius. */
		public Pos spectate;
		public int spectateRadius = 8;
		/** Standing on one for 2 seconds leaves the game early. */
		public List<Pos> exitPads = new ArrayList<>();
		public int minParty = 1;
		public int maxParty = 4;
		/** Waves to clear (0 = endless: the score is how many were cleared). */
		public int rounds = 10;
		/** Deaths the whole party may have (0 = no limit: only everyone down at once loses). */
		public int teamLives = 0;
		/** Seconds between waves. */
		public int breakSeconds = 10;
		/** Generated waves: mob lines ("zombie", "skeleton x2 {…}") picked at random, how many in wave 1, more per wave. */
		public List<String> pool = new ArrayList<>(List.of("zombie", "skeleton", "spider"));
		public int firstWave = 4;
		public int perWave = 2;
		/** % more mobs per player beyond the first. */
		public int perPlayer = 50;
		/** A boss every this many waves (0 = never), and which. */
		public int bossEvery = 5;
		public String boss = "ravager";
		/** % more health and damage per wave after the first. */
		public int toughness = 10;
		/** Mobs alive at once (the rest come as these fall). */
		public int maxAlive = 20;
		/** Hand-written waves replacing the generated ones: "5" → "husk x6, skeleton x2". */
		public Map<String, String> waveLines = new LinkedHashMap<>();
		/** "kit": their own things are put aside and the kit handed out; "own": they fight with what they carry. */
		public String gear = "kit";
		public List<String> kit = new ArrayList<>();
		/** Kit gear: items found inside vanish afterwards (else they go to the inbox). */
		public boolean removePickups = true;
		/** Run as the server for each living player after a wave is cleared ({wave}, {player}, {game}). */
		public List<String> waveCommands = new ArrayList<>();
		/** Shown when the party loses ({wave}: waves cleared). Empty = the default line. */
		public String loseMessage = "";
	}

	public static final class Settings {
		public boolean enabled = true;
		/**
		 * The hub spawn (e.g. in Flying Fair): players who log out or die in a game area, or anywhere in the hub's
		 * dimension, come back here. Null = their home or vanilla respawn.
		 */
		public Pos hub;
		public List<Arena> arenas = new ArrayList<>();

		public Arena arena(String idOrName) {
			for (Arena a : arenas) if (a.id.equals(idOrName) || a.name.equalsIgnoreCase(idOrName)) return a;
			return null;
		}

		public Settings fill() {
			if (arenas == null) arenas = new ArrayList<>();
			arenas.removeIf(a -> a == null);
			Set<String> ids = new HashSet<>();
			for (Arena a : arenas) {
				if (a.id == null || a.id.isBlank() || !ids.add(a.id)) {
					a.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(a.id);
				}
				if (a.name == null) a.name = "";
				if (a.type == null || !TYPES.contains(a.type)) a.type = "parkour";
				if (a.palette == null || a.palette.isEmpty()) a.palette = new Arena().palette;
				if (a.checkpoints == null) a.checkpoints = new ArrayList<>();
				if (a.mode == null || !List.of("run", "boat", "elytra").contains(a.mode)) a.mode = "run";
				if (a.wall == null || a.wall.isBlank()) a.wall = "minecraft:stone_bricks";
				if (a.floor == null || a.floor.isBlank()) a.floor = "minecraft:smooth_stone";
				if (a.regenerate == null || !List.of("run", "daily").contains(a.regenerate)) a.regenerate = "daily";
				a.lanes = Math.max(1, Math.min(32, a.lanes));
				a.laneSpacing = Math.max(16, Math.min(256, a.laneSpacing));
				a.size = Math.max(4, Math.min(40, a.size));
				a.wallHeight = Math.max(1, Math.min(8, a.wallHeight));
				a.ahead = Math.max(1, Math.min(8, a.ahead));
				a.maxGap = Math.max(2, Math.min(5, a.maxGap));
				a.timeLimit = Math.max(0, Math.min(3600, a.timeLimit));
				a.maxTries = Math.max(0, Math.min(999, a.maxTries));
				a.cooldown = Math.max(0, Math.min(3600, a.cooldown));
				if (a.startMessage == null) a.startMessage = "";
				if (a.finishMessage == null) a.finishMessage = "";
				if (a.startCommands == null) a.startCommands = new ArrayList<>();
				if (a.finishCommands == null) a.finishCommands = new ArrayList<>();
				if (a.mobSpawns == null) a.mobSpawns = new ArrayList<>();
				if (a.exitPads == null) a.exitPads = new ArrayList<>();
				a.spectateRadius = Math.max(2, Math.min(64, a.spectateRadius));
				a.minParty = Math.max(1, Math.min(4, a.minParty));
				a.maxParty = Math.max(a.minParty, Math.min(4, a.maxParty));
				a.rounds = Math.max(0, Math.min(1000, a.rounds));
				a.teamLives = Math.max(0, Math.min(1000, a.teamLives));
				a.breakSeconds = Math.max(3, Math.min(300, a.breakSeconds));
				if (a.pool == null) a.pool = new ArrayList<>();
				a.pool.removeIf(x -> x == null || x.isBlank());
				a.firstWave = Math.max(1, Math.min(200, a.firstWave));
				a.perWave = Math.max(0, Math.min(100, a.perWave));
				a.perPlayer = Math.max(0, Math.min(500, a.perPlayer));
				a.bossEvery = Math.max(0, Math.min(1000, a.bossEvery));
				if (a.boss == null) a.boss = "";
				a.toughness = Math.max(0, Math.min(500, a.toughness));
				a.maxAlive = Math.max(1, Math.min(200, a.maxAlive));
				if (a.waveLines == null) a.waveLines = new LinkedHashMap<>();
				a.waveLines.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank() || !e.getKey().matches("\\d{1,4}"));
				if (!"own".equals(a.gear)) a.gear = "kit";
				if (a.kit == null) a.kit = new ArrayList<>();
				if (a.waveCommands == null) a.waveCommands = new ArrayList<>();
				if (a.loseMessage == null) a.loseMessage = "";
			}
			return this;
		}
	}

	/** A player's best in one arena: jumps for parkour (more is better), milliseconds for the others (less is better). */
	public static final class Score {
		public String name;
		public long best;
		public long at;
		public int runs;
	}

	/** Higher scores are better (parkour jumps, waves cleared), else lower times. */
	public static boolean higherIsBetter(String type) {
		return type.equals("parkour") || type.equals("waves");
	}

	/** A score as words: "12 jumps", "5 waves", or a time. */
	public static String scoreText(String type, long score, java.util.function.LongFunction<String> clock) {
		return switch (type) {
			case "parkour" -> score + (score == 1 ? " jump" : " jumps");
			case "waves" -> score + (score == 1 ? " wave" : " waves");
			default -> clock.apply(score);
		};
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Arena a : s.arenas) {
			if (!a.name.matches("[A-Za-z0-9_-]{1,32}")) return "An arena needs a one-word name (letters, digits, - and _).";
			if (!names.add(a.name.toLowerCase(Locale.ROOT))) return "Two arenas are called \"" + a.name + "\".";
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

	/** Scores: arena id → player uuid → best. */
	public static Map<String, Map<String, Score>> readScores(Path file) {
		try {
			if (Files.exists(file)) {
				Map<String, Map<String, Score>> m = Json.GSON.fromJson(Files.readString(file), new com.google.gson.reflect.TypeToken<Map<String, Map<String, Score>>>() { }.getType());
				if (m != null) return m;
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return new LinkedHashMap<>();
	}
}
