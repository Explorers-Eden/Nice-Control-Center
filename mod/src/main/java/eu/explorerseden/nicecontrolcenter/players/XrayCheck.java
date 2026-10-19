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
 *
 * Two kinds of legit players looked the same and are told apart: cave explorers find diamonds with little
 * digging but also mine lots of other ores on the way (x-ray users go for the diamonds alone), and debris
 * miners who blow the netherrack up with beds or TNT, which doesn't count as mined.
 */
public final class XrayCheck {
	/** Counts for one player. otherOres: coal, iron, copper, gold, redstone, lapis, emerald; explosives: TNT and beds used. */
	public record Counts(int diamonds, int stone, int debris, int netherStone, int otherOres, int explosives) {
	}

	private static final Block[] DIAMOND = {Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE};
	private static final Block[] STONE = {Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
			Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.SMOOTH_BASALT, Blocks.COBBLED_DEEPSLATE, Blocks.COBBLESTONE, Blocks.GRAVEL, Blocks.DIRT, Blocks.CLAY};
	private static final Block[] NETHER = {Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK};
	private static final Block[] OTHER_ORES = {Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE,
			Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE,
			Blocks.DEEPSLATE_LAPIS_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE};
	/** Enough ore mined for the numbers to mean something. */
	private static final int MIN_DIAMONDS = 20;
	private static final int MIN_DEBRIS = 10;
	/** Fewer stone blocks per ore than this is suspicious on any server. */
	private static final double DIAMOND_LIMIT = 50;
	private static final double DEBRIS_LIMIT = 12;
	/** Cave explorers mine many other ores per diamond; fewer than this together with little stone is suspicious. */
	private static final double OTHER_ORES_LIMIT = 10;

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
		java.util.function.ToIntFunction<Block> mined = b -> stats.getValue(Stats.BLOCK_MINED, b);
		int explosives = stats.getValue(Stats.ITEM_USED, net.minecraft.world.item.Items.TNT);
		for (var item : BuiltInRegistries.ITEM) {
			if (BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_bed")) explosives += stats.getValue(Stats.ITEM_USED, item);
		}
		return new Counts(sum(mined, DIAMOND), sum(mined, STONE), mined.applyAsInt(Blocks.ANCIENT_DEBRIS), sum(mined, NETHER), sum(mined, OTHER_ORES), explosives);
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
			JsonObject used;
			try (Reader reader = Files.newBufferedReader(file)) {
				JsonObject stats = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats");
				mined = stats == null ? null : stats.getAsJsonObject("minecraft:mined");
				used = stats == null ? null : stats.getAsJsonObject("minecraft:used");
			}
			JsonObject m = mined == null ? new JsonObject() : mined;
			java.util.function.ToIntFunction<Block> get = b -> {
				var e = m.get(BuiltInRegistries.BLOCK.getKey(b).toString());
				return e == null ? 0 : e.getAsInt();
			};
			int explosives = 0;
			if (used != null) {
				for (var e : used.entrySet()) {
					if (e.getKey().equals("minecraft:tnt") || e.getKey().endsWith("_bed")) explosives += e.getValue().getAsInt();
				}
			}
			Counts counts = new Counts(sum(get, DIAMOND), sum(get, STONE), get.applyAsInt(Blocks.ANCIENT_DEBRIS), sum(get, NETHER), sum(get, OTHER_ORES), explosives);
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
			double others = counts.otherOres() / (double) counts.diamonds();
			result.put("diamondRatio", ratio);
			result.put("otherOresRatio", others);
			boolean low = ratio < DIAMOND_LIMIT || diamondMedian != null && ratio < diamondMedian / 5 && ratio < 150;
			// Little stone and few other ores per diamond: going straight for the diamonds.
			if (low && others < OTHER_ORES_LIMIT) {
				reasons.add("diamonds");
			}
		}
		if (counts.debris() >= MIN_DEBRIS) {
			double ratio = counts.netherStone() / (double) counts.debris();
			result.put("debrisRatio", ratio);
			boolean low = ratio < DEBRIS_LIMIT || debrisMedian != null && ratio < debrisMedian / 5 && ratio < 30;
			// Beds and TNT blow netherrack away without counting it as mined.
			boolean blasting = counts.explosives() * 3 >= counts.debris();
			result.put("blasting", blasting);
			if (low && !blasting) {
				reasons.add("debris");
			}
		}
		result.put("diamonds", counts.diamonds());
		result.put("stone", counts.stone());
		result.put("debris", counts.debris());
		result.put("netherStone", counts.netherStone());
		result.put("otherOres", counts.otherOres());
		result.put("explosives", counts.explosives());
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
