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
 * Mini-game arenas (templates: infinite parkour, race course, dropper, maze, wave fight, the quick rounds ghast
 * deflect, colour floor, anvil rain, treasure grab and fishing frenzy, and head hunts), kept in
 * config/nicecontrolcenter-games.json: made with /game in game, changed in the panel's Games page. Scores are kept
 * by the mod in the world folder (nicecontrolcenter-games-scores.json). Minecraft-free so both compile it.
 */
public final class Games {
	public static final String FILE = "nicecontrolcenter-games.json";
	public static final String SCORES = "nicecontrolcenter-games-scores.json";
	public static final List<String> TYPES = List.of("parkour", "race", "dropper", "maze", "waves", "ghasts", "colorfloor", "anvils", "treasure", "fishing", "heads");
	/** Quick rounds: everyone on the start pad during the join window plays the same round, each for their own score. */
	public static final List<String> QUICK = List.of("ghasts", "colorfloor", "anvils", "treasure", "fishing");

	public static boolean quick(String type) {
		return QUICK.contains(type);
	}

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

		/** Squared distance to another spot (same dimension assumed). */
		public double distanceSq(Pos o) {
			double dx = x - o.x, dy = y - o.y, dz = z - o.z;
			return dx * dx + dy * dy + dz * dz;
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
		/** One word, for the commands (/game join <name>). */
		public String name = "";
		/** What players see (spaces allowed); empty = the name. */
		public String title = "";
		/** An optional line under the name on its entrances ("Beat the record!"); empty = none. */
		public String subtitle = "";

		public String shown() {
			return title == null || title.isBlank() ? name : title.strip();
		}

		/** Points (reward points) when a score counts: this many, plus so many per score (not for timed games). */
		public int pointsFixed = 0;
		public double pointsPerScore = 0;

		/** The game's colour ("#ffc83d"): its entrance title and particles, watch point, start title; empty = gold. */
		public String color = "";

		/** The colour as RGB (gold when none or unreadable). */
		public int rgb() {
			if (color != null && color.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(color.replace("#", ""), 16);
			return 0xFFC83D;
		}

		/** parkour, race, dropper or maze. */
		public String type = "parkour";
		public boolean enabled = true;
		/** The start point: where the game begins (stepping on it starts a run; where /game join and the entrances bring players). */
		public Pos start;
		/** Entrances (e.g. in a hub): stepping in takes players to the game; they show its name and top 3 above them. */
		public List<Pos> entrances = new ArrayList<>();
		/** Watch points: stepping in takes players to the spectator area (spectate). */
		public List<Pos> watchEntrances = new ArrayList<>();
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
		/** Maze: the corner it's built from, cells per side (4–256), the blocks (comma-separated: mixed at random), and when it's made new ("run" or "daily"). */
		public Pos corner;
		public int size = 12;
		public String wall = "minecraft:stone_bricks";
		public String floor = "minecraft:smooth_stone";
		public String regenerate = "daily";
		/** Maze: wall height in blocks. */
		public int wallHeight = 3;
		/** Maze: how light its paths are (0 = dark: no lights, up to 15). */
		public int mazeLight = 0;
		/** Parkour: a reward every this many jumps (0 = none): everyCommands ({jumps}, {player}, {game}). The box (corner1/2) is optional. */
		public int everyJumps = 0;
		/**
		 * Parkour: how fast it gets harder: "easy", "normal", "hard" or "extreme" (levels come quicker and go higher:
		 * longer gaps, up and down, diagonal jumps, then slabs and thin blocks); only jumps that can be made.
		 */
		public String difficulty = "normal";
		/** Race: commands at each checkpoint ({checkpoint}, {player}, {game}). */
		public List<String> checkpointCommands = new ArrayList<>();
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
		/** Optional: the spectator area as a box (two corners, height included) instead of the radius. */
		public Pos spectateMin;
		public Pos spectateMax;
		/** Back points: inside a game, standing on one for 5 seconds leaves it; elsewhere it takes players back where they came from. */
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
		/** Peaceful and neutral mobs (cows, villagers, iron golems, llamas, bees…) attack players too (waves, colour floor). */
		public boolean peacefulFight = true;
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

		// Quick rounds (ghasts, colorfloor, anvils, treasure, fishing); they also use spawn, the box, mobSpawns, pool,
		// maxAlive, timeLimit (0 = until the last one is out), exitPads and gear/kit.
		/** Seconds the start pad waits for more players once the first one stepped on it; most players in a round. */
		public int joinSeconds = 10;
		public int maxPlayers = 8;
		/** Colour floor: the 3×3 grid of platforms (corner = its lowest north-west block), their size and the gap between. */
		public List<String> colors = new ArrayList<>(List.of("minecraft:red_wool", "minecraft:orange_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
				"minecraft:light_blue_wool", "minecraft:blue_wool", "minecraft:purple_wool", "minecraft:pink_wool", "minecraft:white_wool"));
		public int platformSize = 5;
		public int platformGap = 2;
		/** Colour floor: seconds before the called colour vanishes, how much faster after each refill, and the fastest. */
		public double roundSeconds = 6;
		public double speedUp = 0.5;
		public double minRoundSeconds = 2;
		/** Colour floor: colours that vanish before the grid is filled again; a mob from the pool every … colours (0 = none). */
		public int refillEvery = 4;
		public int mobsEvery = 3;
		/** Anvil rain: anvils a second at the start, and % more every 10 seconds. */
		public double anvilRate = 2;
		public int anvilGrowth = 25;
		/** Treasure grab: "minecraft:diamond=5" (item = points); rarer the more it's worth. */
		public List<String> treasureItems = new ArrayList<>(List.of("minecraft:emerald=1", "minecraft:gold_ingot=2", "minecraft:diamond=5"));
		/** Fishing frenzy: points for a fish, a pufferfish or tropical fish, a treasure, and junk. */
		public int fishPoints = 1;
		public int rareFishPoints = 3;
		public int treasurePoints = 5;
		public int junkPoints = 0;

		// Head hunt: player heads hidden in the world; right-click to find one.
		/** The heads' skin: a textures.minecraft.net URL, its hash, or the base64 value. */
		public String headTexture = "";
		/** Where the heads are (block positions). */
		public List<Pos> heads = new ArrayList<>();
		/** A reward every this many heads found (0 = none): everyCommands ({found}, {total}, {player}, {game}). */
		public int rewardEvery = 5;
		public List<String> everyCommands = new ArrayList<>();
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
				if (a.title == null) a.title = "";
				if (a.subtitle == null) a.subtitle = "";
				// Colour floor: players gather on the middle platform (not where it was made).
				if (a.type.equals("colorfloor") && a.corner != null) {
					int step = Math.max(1, a.platformSize) + Math.max(0, a.platformGap);
					a.start = new Pos(a.corner.dim, a.corner.x + step + a.platformSize / 2.0, a.corner.y + 1, a.corner.z + step + a.platformSize / 2.0, 0);
				}
				// Wave fights and quick rounds made before the spawn was set on creating: the spot they were made on.
				if (a.spawn == null && a.start != null && (a.type.equals("waves") || quick(a.type) && !a.type.equals("colorfloor"))) {
					a.spawn = new Pos(a.start.dim, a.start.x, a.start.y, a.start.z, a.start.yaw);
				}
				if (a.color == null || !a.color.matches("#?[0-9a-fA-F]{6}")) a.color = "";
				a.pointsFixed = Math.max(0, Math.min(1_000_000, a.pointsFixed));
				a.pointsPerScore = Math.max(0, Math.min(10_000, a.pointsPerScore));
				if (a.type == null || !TYPES.contains(a.type)) a.type = "parkour";
				if (a.palette == null || a.palette.isEmpty()) a.palette = new Arena().palette;
				if (a.checkpoints == null) a.checkpoints = new ArrayList<>();
				if (a.mode == null || !List.of("run", "boat", "elytra").contains(a.mode)) a.mode = "run";
				if (a.wall == null || a.wall.isBlank()) a.wall = "minecraft:stone_bricks";
				if (a.floor == null || a.floor.isBlank()) a.floor = "minecraft:smooth_stone";
				if (a.regenerate == null || !List.of("run", "daily").contains(a.regenerate)) a.regenerate = "daily";
				a.lanes = Math.max(1, Math.min(32, a.lanes));
				a.laneSpacing = Math.max(16, Math.min(256, a.laneSpacing));
				a.size = Math.max(4, Math.min(256, a.size));
				a.wallHeight = Math.max(1, Math.min(8, a.wallHeight));
				a.mazeLight = Math.max(0, Math.min(15, a.mazeLight));
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
				a.joinSeconds = Math.max(0, Math.min(120, a.joinSeconds));
				a.maxPlayers = Math.max(1, Math.min(32, a.maxPlayers));
				if (a.colors == null) a.colors = new ArrayList<>();
				a.colors.removeIf(x -> x == null || x.isBlank());
				if (a.colors.isEmpty()) a.colors = new Arena().colors;
				a.platformSize = Math.max(1, Math.min(16, a.platformSize));
				a.platformGap = Math.max(0, Math.min(16, a.platformGap));
				a.roundSeconds = Math.max(1, Math.min(60, a.roundSeconds));
				a.speedUp = Math.max(0, Math.min(30, a.speedUp));
				a.minRoundSeconds = Math.max(1, Math.min(a.roundSeconds, a.minRoundSeconds));
				a.refillEvery = Math.max(1, Math.min(8, a.refillEvery));
				a.mobsEvery = Math.max(0, Math.min(100, a.mobsEvery));
				a.anvilRate = Math.max(0.1, Math.min(50, a.anvilRate));
				a.anvilGrowth = Math.max(0, Math.min(500, a.anvilGrowth));
				if (a.treasureItems == null) a.treasureItems = new ArrayList<>();
				a.treasureItems.removeIf(x -> x == null || x.isBlank());
				a.fishPoints = Math.max(0, Math.min(1000, a.fishPoints));
				a.rareFishPoints = Math.max(0, Math.min(1000, a.rareFishPoints));
				a.treasurePoints = Math.max(0, Math.min(1000, a.treasurePoints));
				a.junkPoints = Math.max(0, Math.min(1000, a.junkPoints));
				if (a.headTexture == null) a.headTexture = "";
				if (a.heads == null) a.heads = new ArrayList<>();
				a.heads.removeIf(x -> x == null || x.dim == null);
				a.rewardEvery = Math.max(0, Math.min(10_000, a.rewardEvery));
				a.everyJumps = Math.max(0, Math.min(10_000, a.everyJumps));
				if (a.entrances == null) a.entrances = new ArrayList<>();
				if (a.watchEntrances == null) a.watchEntrances = new ArrayList<>();
				a.watchEntrances.removeIf(x -> x == null || x.dim == null);
				a.entrances.removeIf(x -> x == null || x.dim == null);
				if (a.difficulty == null || !List.of("easy", "normal", "hard", "extreme").contains(a.difficulty)) a.difficulty = "normal";
				if (a.checkpointCommands == null) a.checkpointCommands = new ArrayList<>();
				if (a.everyCommands == null) a.everyCommands = new ArrayList<>();
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

	/** Higher scores are better (parkour jumps, waves cleared, quick rounds, heads found), else lower times. */
	public static boolean higherIsBetter(String type) {
		return type.equals("parkour") || type.equals("waves") || quick(type) || type.equals("heads");
	}

	/** A score as words: "12 jumps", "5 waves", "3 ghasts", "47 s", or a time. */
	public static String scoreText(String type, long score, java.util.function.LongFunction<String> clock) {
		return switch (type) {
			case "parkour" -> score + (score == 1 ? " jump" : " jumps");
			case "waves" -> score + (score == 1 ? " wave" : " waves");
			case "ghasts" -> score + (score == 1 ? " ghast" : " ghasts");
			case "colorfloor" -> score + (score == 1 ? " colour" : " colours");
			case "anvils" -> score + " s";
			case "treasure", "fishing" -> score + (score == 1 ? " point" : " points");
			case "heads" -> score + (score == 1 ? " head" : " heads");
			default -> clock.apply(score);
		};
	}

	private static final java.util.regex.Pattern MATH = java.util.regex.Pattern.compile("\\{(\\w+)\\s*([*/+-])\\s*(\\d+(?:\\.\\d+)?)\\}");

	/**
	 * Sums in commands: {score*2}, {score/10}, {jumps+5}, {wave-1} with the value of that name (whole numbers,
	 * rounded); names that aren't known, or aren't numbers, stay as written.
	 */
	public static String math(String text, Map<String, String> values) {
		if (text == null || text.indexOf('{') < 0) return text;
		java.util.regex.Matcher m = MATH.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String v = values.get(m.group(1));
			double n;
			try {
				n = v == null ? Double.NaN : Double.parseDouble(v.replace(",", ""));
			} catch (NumberFormatException e) {
				n = Double.NaN;
			}
			if (Double.isNaN(n)) {
				m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group()));
				continue;
			}
			double by = Double.parseDouble(m.group(3));
			double r = switch (m.group(2)) {
				case "*" -> n * by;
				case "/" -> by == 0 ? n : n / by;
				case "+" -> n + by;
				default -> n - by;
			};
			m.appendReplacement(out, String.valueOf(Math.round(r)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** In the arena's spectator area: its box when both corners are set, else within the radius of its point (plus slack). */
	public static boolean inSpectate(Arena a, String dim, double x, double y, double z, double slack) {
		if (a.spectateMin != null && a.spectateMax != null && a.spectateMin.dim.equals(a.spectateMax.dim)) {
			if (!dim.equals(a.spectateMin.dim)) return false;
			return x >= Math.min(a.spectateMin.x, a.spectateMax.x) - slack && x <= Math.max(a.spectateMin.x, a.spectateMax.x) + 1 + slack
					&& y >= Math.min(a.spectateMin.y, a.spectateMax.y) - slack && y <= Math.max(a.spectateMin.y, a.spectateMax.y) + 1 + slack
					&& z >= Math.min(a.spectateMin.z, a.spectateMax.z) - slack && z <= Math.max(a.spectateMin.z, a.spectateMax.z) + 1 + slack;
		}
		Pos s = a.spectate;
		double r = a.spectateRadius + slack;
		return s != null && dim.equals(s.dim) && (x - s.x) * (x - s.x) + (y - s.y) * (y - s.y) + (z - s.z) * (z - s.z) <= r * r;
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Set<String> names = new HashSet<>();
		for (Arena a : s.arenas) {
			if (!a.name.matches("[A-Za-z0-9_-]{1,32}")) return "An arena needs a one-word name (letters, digits, - and _).";
			if (!names.add(a.name.toLowerCase(Locale.ROOT))) return "Two arenas are called \"" + a.name + "\".";
			if (a.title.length() > 48) return "The display name of " + a.name + " is up to 48 characters.";
			if (a.subtitle.length() > 80) return "The entrance line of " + a.name + " is up to 80 characters.";
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
