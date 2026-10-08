package eu.explorerseden.nicecontrolcenter.players;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A hint, not proof, for x-ray: how much ordinary stone a player dug per diamond ore or ancient debris,
 * from the game's own statistics. Legit players dig through a lot of stone per ore; x-ray users go
 * almost straight to it. Compared with fixed limits and with the server's own players.
 */
public final class XrayCheck {
	/** Counts for one player. */
	public record Counts(int diamonds, int stone, int debris, int netherStone) {
	}

	private static final Block[] DIAMOND = {Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE};
	private static final Block[] STONE = {Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE};
	private static final Block[] NETHER = {Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE};
	/** Enough ore mined for the numbers to mean something. */
	private static final int MIN_DIAMONDS = 15;
	private static final int MIN_DEBRIS = 8;
	/** Fewer stone blocks per ore than this is suspicious on any server. */
	private static final double DIAMOND_LIMIT = 80;
	private static final double DEBRIS_LIMIT = 15;

	private record Cached(long modified, Counts counts) {
	}

	private static final Map<Path, Cached> files = new ConcurrentHashMap<>();
	private static volatile Map<String, Object> reference = Map.of();
	private static volatile long referenceAt;

	private XrayCheck() {
	}

	/** Forget the comparison values, e.g. when another world is opened. */
	public static void reset() {
		reference = Map.of();
		referenceAt = 0;
		files.clear();
	}

	/** Live counts of an online player. Server thread. */
	public static Counts counts(ServerPlayer player) {
		var stats = player.getStats();
		return new Counts(sum(b -> stats.getValue(Stats.BLOCK_MINED, b), DIAMOND), sum(b -> stats.getValue(Stats.BLOCK_MINED, b), STONE),
				stats.getValue(Stats.BLOCK_MINED, Blocks.ANCIENT_DEBRIS), sum(b -> stats.getValue(Stats.BLOCK_MINED, b), NETHER));
	}

	/** Counts from an offline player's saved statistics, cached until the file changes. Any thread. */
	public static Counts counts(MinecraftServer server, UUID player) {
		Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(player + ".json");
		try {
			if (!Files.exists(file)) {
				return null;
			}
			long modified = Files.getLastModifiedTime(file).toMillis();
			Cached cached = files.get(file);
			if (cached != null && cached.modified() == modified) {
				return cached.counts();
			}
			JsonObject mined;
			try (Reader reader = Files.newBufferedReader(file)) {
				mined = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats").getAsJsonObject("minecraft:mined");
			}
			JsonObject m = mined == null ? new JsonObject() : mined;
			java.util.function.ToIntFunction<Block> get = b -> {
				var e = m.get(BuiltInRegistries.BLOCK.getKey(b).toString());
				return e == null ? 0 : e.getAsInt();
			};
			Counts counts = new Counts(sum(get, DIAMOND), sum(get, STONE), get.applyAsInt(Blocks.ANCIENT_DEBRIS), sum(get, NETHER));
			files.put(file, new Cached(modified, counts));
			return counts;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static int sum(java.util.function.ToIntFunction<Block> get, Block[] blocks) {
		int total = 0;
		for (Block block : blocks) {
			total += get.applyAsInt(block);
		}
		return total;
	}

	/**
	 * The result for one player: the numbers, the server's typical values and whether it looks like
	 * x-ray. Null if there are no statistics.
	 */
	public static Map<String, Object> judge(MinecraftServer server, Counts counts) {
		if (counts == null) {
			return null;
		}
		Map<String, Object> ref = reference(server);
		Map<String, Object> result = new LinkedHashMap<>();
		Double diamondMedian = (Double) ref.get("diamondMedian");
		Double debrisMedian = (Double) ref.get("debrisMedian");
		List<String> reasons = new ArrayList<>();
		if (counts.diamonds() >= MIN_DIAMONDS) {
			double ratio = counts.stone() / (double) counts.diamonds();
			result.put("diamondRatio", ratio);
			boolean low = ratio < DIAMOND_LIMIT || diamondMedian != null && ratio < diamondMedian / 4 && ratio < 200;
			if (low) {
				reasons.add("diamonds");
			}
		}
		if (counts.debris() >= MIN_DEBRIS) {
			double ratio = counts.netherStone() / (double) counts.debris();
			result.put("debrisRatio", ratio);
			boolean low = ratio < DEBRIS_LIMIT || debrisMedian != null && ratio < debrisMedian / 4 && ratio < 40;
			if (low) {
				reasons.add("debris");
			}
		}
		result.put("diamonds", counts.diamonds());
		result.put("stone", counts.stone());
		result.put("debris", counts.debris());
		result.put("netherStone", counts.netherStone());
		result.put("diamondMedian", diamondMedian);
		result.put("debrisMedian", debrisMedian);
		result.put("players", ref.get("players"));
		result.put("minDiamonds", MIN_DIAMONDS);
		result.put("minDebris", MIN_DEBRIS);
		result.put("flags", reasons);
		return result;
	}

	/** Typical stone per ore among all players with enough ore mined; refreshed once a minute. */
	private static Map<String, Object> reference(MinecraftServer server) {
		if (System.currentTimeMillis() - referenceAt < 60_000) {
			return reference;
		}
		List<Double> diamond = new ArrayList<>();
		List<Double> debris = new ArrayList<>();
		Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		if (Files.isDirectory(dir)) {
			try (Stream<Path> list = Files.list(dir)) {
				for (Path file : list.filter(f -> f.toString().endsWith(".json")).toList()) {
					String name = file.getFileName().toString();
					try {
						Counts c = counts(server, UUID.fromString(name.substring(0, name.length() - 5)));
						if (c != null && c.diamonds() >= MIN_DIAMONDS) {
							diamond.add(c.stone() / (double) c.diamonds());
						}
						if (c != null && c.debris() >= MIN_DEBRIS) {
							debris.add(c.netherStone() / (double) c.debris());
						}
					} catch (IllegalArgumentException e) {
						// Not a player file.
					}
				}
			} catch (IOException e) {
				// No reference this time.
			}
		}
		Map<String, Object> ref = new LinkedHashMap<>();
		// A median only says something with a few players to compare.
		ref.put("diamondMedian", diamond.size() >= 3 ? median(diamond) : null);
		ref.put("debrisMedian", debris.size() >= 3 ? median(debris) : null);
		ref.put("players", Math.max(diamond.size(), debris.size()));
		reference = ref;
		referenceAt = System.currentTimeMillis();
		return ref;
	}

	private static Double median(List<Double> values) {
		List<Double> sorted = new ArrayList<>(values);
		sorted.sort(Double::compare);
		int n = sorted.size();
		return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
	}
}
