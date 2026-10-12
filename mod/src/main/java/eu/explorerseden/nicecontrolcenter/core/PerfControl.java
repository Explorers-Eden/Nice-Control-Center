package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Performance;
import eu.explorerseden.nicecontrolcenter.web.PanelBridge;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Applies config/nicecontrolcenter-performance.json (see {@link Performance}) while the server runs: the
 * dynamic distances controller, the activation range and frozen chunk checks the mixins ask for, merge
 * radii, breeding caps and villager brains. All checks run on the server thread.
 */
public final class PerfControl {
	private static volatile Performance.Settings settings = new Performance.Settings();
	private static long modified = -1;
	/** Frozen chunks by dimension id. */
	private static Map<String, LongSet> frozen = Map.of();
	/** Player positions per level for this tick (x, z pairs), for the activation range. */
	private static final Map<ServerLevel, double[]> players = new IdentityHashMap<>();
	private static final Map<Villager, long[]> trapped = new WeakHashMap<>();

	// Dynamic distances: the values while lowered, and the server's own ones to go back to.
	private static int baseSim = -1;
	private static int baseView = -1;
	private static int sim = -1;
	private static int view = -1;
	private static volatile int mobCap = 100;
	private static long lastStep;
	private static long calmSince = -1;
	private static volatile String lastChange = "";

	private PerfControl() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Performance.FILE);
	}

	public static Performance.Settings settings() {
		return settings;
	}

	public static void reset() {
		modified = -1;
		baseSim = baseView = sim = view = -1;
		mobCap = 100;
		calmSince = -1;
		lastChange = "";
		players.clear();
		trapped.clear();
	}

	/** Start of every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (modified < 0 || tick % 100 == 0) reload(server);
		players.clear();
		if (settings.activation.enabled) {
			for (ServerLevel level : server.getAllLevels()) {
				var list = level.players();
				double[] xz = new double[list.size() * 2];
				for (int i = 0; i < list.size(); i++) {
					ServerPlayer p = list.get(i);
					xz[i * 2] = p.getX();
					xz[i * 2 + 1] = p.getZ();
				}
				players.put(level, xz);
			}
		}
		if (tick % 20 == 0) dynamic(server);
	}

	private static void reload(MinecraftServer server) {
		Path file = file();
		long now;
		try {
			now = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		Performance.Settings next = Performance.read(file);
		Map<String, LongSet> map = new HashMap<>();
		for (Performance.Chunk c : next.frozen) map.computeIfAbsent(c.dimension, k -> new LongOpenHashSet()).add(ChunkPos.pack(c.x, c.z));
		frozen = map;
		settings = next;
		if (!next.dynamic.enabled) restore(server, "dynamic distances switched off");
		if (!first || now != 0) NiceControlCenter.LOGGER.info("Performance settings loaded ({})", summary(next));
	}

	private static String summary(Performance.Settings s) {
		StringBuilder b = new StringBuilder();
		if (s.dynamic.enabled) b.append("dynamic distances, ");
		if (s.activation.enabled) b.append("activation range, ");
		if (s.merge.enabled) b.append("merge radius, ");
		if (s.breeding.enabled) b.append("breeding caps, ");
		if (s.villagers.lobotomize) b.append("villager brains, ");
		if (!s.frozen.isEmpty()) b.append(s.frozen.size()).append(" frozen chunks, ");
		return b.isEmpty() ? "all off" : b.substring(0, b.length() - 2);
	}

	// ── Dynamic distances ──────────────────────────────────────────────

	private static void dynamic(MinecraftServer server) {
		Performance.Dynamic d = settings.dynamic;
		if (!d.enabled) return;
		var list = server.getPlayerList();
		if (baseSim < 0) {
			baseSim = sim = list.getSimulationDistance();
			baseView = view = list.getViewDistance();
		}
		double ms = server.getAverageTickTimeNanos() / 1_000_000.0;
		long now = System.currentTimeMillis();
		if (ms > d.targetMspt) {
			calmSince = -1;
			if (now - lastStep < 10_000) return;
			String what = null;
			if (sim > Math.min(d.simulationMin, baseSim)) {
				sim--;
				list.setSimulationDistance(sim);
				what = "simulation distance " + sim;
			} else if (mobCap > d.mobCapMin) {
				mobCap = Math.max(d.mobCapMin, mobCap - 10);
				what = "mob cap " + mobCap + " %";
			} else if (d.view && view > Math.min(d.viewMin, baseView)) {
				view--;
				list.setViewDistance(view);
				what = "view distance " + view;
			}
			if (what != null) step(now, "Lag (" + Math.round(ms) + " ms per tick): lowered the " + what);
			return;
		}
		if (ms >= d.recoverMspt || !lowered()) {
			calmSince = -1;
			return;
		}
		if (calmSince < 0) calmSince = now;
		if (now - calmSince < 60_000 || now - lastStep < 30_000) return;
		String what;
		if (view < baseView) {
			view++;
			list.setViewDistance(view);
			what = "view distance " + view;
		} else if (mobCap < 100) {
			mobCap = Math.min(100, mobCap + 10);
			what = "mob cap " + mobCap + " %";
		} else {
			sim++;
			list.setSimulationDistance(sim);
			what = "simulation distance " + sim;
		}
		step(now, "Recovered (" + Math.round(ms) + " ms per tick): raised the " + what + (lowered() ? "" : ", back to normal"));
	}

	private static boolean lowered() {
		return baseSim >= 0 && (sim < baseSim || view < baseView || mobCap < 100);
	}

	private static void step(long now, String text) {
		lastStep = now;
		lastChange = text;
		NiceControlCenter.LOGGER.info(text);
		PanelBridge.alert("perf", text);
	}

	/** Puts the server's own distances back (dynamic off, or the server stops). */
	public static void restore(MinecraftServer server, String why) {
		if (!lowered()) {
			baseSim = baseView = sim = view = -1;
			return;
		}
		server.getPlayerList().setSimulationDistance(baseSim);
		server.getPlayerList().setViewDistance(baseView);
		mobCap = 100;
		NiceControlCenter.LOGGER.info("Distances back to normal ({})", why);
		lastChange = "Back to normal (" + why + ")";
		baseSim = baseView = sim = view = -1;
	}

	/** For the dashboard and the panel: the current level of the dynamic controller. */
	public static Map<String, Object> state(MinecraftServer server) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		var list = server.getPlayerList();
		out.put("enabled", settings.dynamic.enabled);
		out.put("simulation", list.getSimulationDistance());
		out.put("view", list.getViewDistance());
		out.put("simulationBase", baseSim >= 0 ? baseSim : list.getSimulationDistance());
		out.put("viewBase", baseView >= 0 ? baseView : list.getViewDistance());
		out.put("mobCap", mobCap);
		out.put("lowered", lowered());
		out.put("mspt", Math.round(server.getAverageTickTimeNanos() / 100_000.0) / 10.0);
		out.put("lastChange", lastChange);
		out.put("frozen", settings.frozen.size());
		return out;
	}

	/** Mob caps in percent (the spawner mixins scale vanilla's caps with it). */
	public static int mobCap() {
		return mobCap;
	}

	// ── Ticking: frozen chunks and activation range ────────────────────────

	public static boolean frozen(ServerLevel level, int chunkX, int chunkZ) {
		if (frozen.isEmpty()) return false;
		LongSet set = frozen.get(level.dimension().identifier().toString());
		return set != null && set.contains(ChunkPos.pack(chunkX, chunkZ));
	}

	/** Whether to skip this entity's tick: in a frozen chunk, or far from players and not its turn. */
	public static boolean skip(ServerLevel level, Entity entity) {
		if (entity instanceof Player) return false;
		// A vehicle may carry a player, who must keep ticking.
		if (!frozen.isEmpty() && !entity.isVehicle() && frozen(level, entity.getBlockX() >> 4, entity.getBlockZ() >> 4)) return true;
		Performance.Activation a = settings.activation;
		if (!a.enabled || !(entity instanceof Mob mob)) return false;
		if ((entity.tickCount + entity.getId()) % a.every == 0) return false;
		if (immune(mob)) return false;
		int range = entity instanceof Enemy ? a.monsters : entity instanceof AbstractVillager ? a.villagers : entity instanceof Animal ? a.animals : a.other;
		double[] xz = players.get(level);
		if (xz == null) return false;
		double r2 = (double) range * range;
		for (int i = 0; i < xz.length; i += 2) {
			double dx = xz[i] - entity.getX();
			double dz = xz[i + 1] - entity.getZ();
			if (dx * dx + dz * dz <= r2) return false;
		}
		return true;
	}

	/** Entities that always tick: anything in motion through the air or water, fighting, burning, tied or carried. */
	private static boolean immune(Mob mob) {
		if (mob instanceof EnderDragon || mob instanceof WitherBoss) return true;
		if (mob.isPassenger() || mob.isVehicle() || !mob.onGround() || mob.isInWater() || mob.isOnFire()) return true;
		if (mob.hurtTime > 0 || mob.getTarget() != null) return true;
		if (mob instanceof Leashable l && l.isLeashed()) return true;
		return mob instanceof Raider raider && raider.hasActiveRaid();
	}

	// ── Merging, breeding, villagers ─────────────────────────────────────

	/** How far items look for others to merge with (vanilla 0.5). */
	public static double itemMerge() {
		Performance.Merge m = settings.merge;
		return m.enabled ? m.items : 0.5;
	}

	public static double xpMerge() {
		Performance.Merge m = settings.merge;
		return m.enabled ? m.xp : 0.5;
	}

	/** False when too many of this kind live nearby to breed more. */
	public static boolean mayBreed(Entity entity) {
		Performance.Breeding b = settings.breeding;
		if (!b.enabled || !(entity.level() instanceof ServerLevel level)) return true;
		int cap = entity instanceof AbstractVillager ? b.villagers : b.animals;
		AABB box = entity.getBoundingBox().inflate(b.radius);
		int count = level.getEntities(entity.getType(), box, e -> true).size();
		return count < cap;
	}

	/** Whether a villager's brain runs this tick. */
	public static boolean villagerThinks(Villager villager) {
		Performance.Villagers v = settings.villagers;
		if (!v.lobotomize || (villager.tickCount + villager.getId()) % v.every == 0) return true;
		long[] cached = trapped.get(villager);
		long now = villager.tickCount;
		if (cached == null || now - cached[0] >= 100 || now < cached[0]) {
			cached = new long[] { now, isTrapped(villager) ? 1 : 0 };
			trapped.put(villager, cached);
		}
		return cached[1] == 0;
	}

	/** In a vehicle, or every side at foot and head height is blocked. */
	private static boolean isTrapped(Villager villager) {
		if (villager.isPassenger()) return true;
		if (!(villager.level() instanceof ServerLevel level)) return false;
		BlockPos feet = villager.blockPosition();
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(dir);
			if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()
					&& level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
