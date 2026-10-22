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
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
import java.util.HashSet;
import java.util.Set;
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
	/** What the file says. */
	private static Performance.Settings fileSettings = new Performance.Settings();
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
	/**
	 * The far-mob ranges (Tick less where nobody is, Lighter mob AI) while lag protection is on: step 0 is the
	 * whole simulation distance, RANGE_STEPS the percentages set in the panel. Lag protection's first steps.
	 */
	private static volatile int rangeStep;
	private static final int RANGE_STEPS = 3;
	// The extra step before the view distance: entity tracking at 60 %.
	private static volatile int tracking = 100;
	// Resolved on reload, for the checks that run per entity.
	private static Set<Item> junk = Set.of();
	private static Map<EntityType<?>, Integer> saveLimits = Map.of();
	private static Cleanup.Limits limits = new Cleanup.Limits(java.util.List.of());
	// Auto caps: what went in the last hour, and when the panel last heard about it.
	private static final java.util.ArrayDeque<long[]> capped = new java.util.ArrayDeque<>();
	private static long lastCapAlert;
	// New path searches this tick (the path budget).
	private static int paths;
	private static long pathsTick = -1;
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
		rangeStep = 0;
		tracking = 100;
		taken.clear();
		calmSince = -1;
		lastChange = "";
		capped.clear();
		players.clear();
		trapped.clear();
	}

	/** Activation and "far from players" ranges never go below this many blocks. */
	private static final int MIN_RANGE = 16;
	/** The simulation distance in blocks right now (lag protection may have lowered it). */
	private static int simulationBlocks = 160;

	/**
	 * Blocks for a far-mob range: a percentage of the current simulation distance. With lag protection on,
	 * the set percentage is the lowest it goes: without lag it's the whole simulation distance, and each lag
	 * step brings it closer to the set one.
	 */
	private static int rangeOf(int percent) {
		int p = Math.min(100, percent);
		if (settings.dynamic.enabled) p = 100 - (100 - p) * rangeStep / RANGE_STEPS;
		return Math.max(MIN_RANGE, simulationBlocks * p / 100);
	}

	private static boolean rangesUsed() {
		return settings.activation.enabled || settings.ai.enabled;
	}

	/** Start of every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (modified < 0 || tick % 100 == 0) reload(server);
		simulationBlocks = server.getPlayerList().getSimulationDistance() * 16;
		players.clear();
		if (settings.activation.enabled || settings.ai.enabled) {
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
		Performance.AutoCaps caps = settings.autoCaps;
		if (caps.enabled && tick > 0 && tick % (caps.intervalSeconds * 20) == 0) autoCaps(server, caps);
	}

	/** The mob limits and the dropped item limit, applied on their own. */
	private static void autoCaps(MinecraftServer server, Performance.AutoCaps caps) {
		int mobs = limits.isEmpty() ? 0 : (int) Cleanup.excess(server, limits, true).get("remove");
		int items = caps.items ? Cleanup.itemsOver(server, caps.itemsPerChunk) : 0;
		long now = System.currentTimeMillis();
		while (!capped.isEmpty() && now - capped.peekFirst()[0] > 3_600_000L) capped.pollFirst();
		if (mobs + items == 0) return;
		capped.addLast(new long[] { now, mobs, items });
		if (now - lastCapAlert >= 600_000L) {
			lastCapAlert = now;
			String text = "Auto caps removed " + (mobs > 0 ? mobs + " mobs over their limit" : "") + (mobs > 0 && items > 0 ? " and " : "")
					+ (items > 0 ? items + " dropped items" : "");
			NiceControlCenter.LOGGER.info(text);
			PanelBridge.alert("perf", text);
		}
	}

	private static void reload(MinecraftServer server) {
		Path file = file();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file);
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		fileSettings = Performance.read(file);
		apply(server);
		if (!first || now != 0) NiceControlCenter.LOGGER.info("Performance settings loaded ({})", summary(settings));
	}

	/** Uses the file's settings: the item, mob and save lists they name, then the settings themselves. */
	private static void apply(MinecraftServer server) {
		Performance.Settings next = fileSettings;
		Map<String, LongSet> map = new HashMap<>();
		for (Performance.Chunk c : next.frozen) map.computeIfAbsent(c.dimension, k -> new LongOpenHashSet()).add(ChunkPos.pack(c.x, c.z));
		frozen = map;
		Set<Item> items = new HashSet<>();
		for (String id : next.despawn.items) {
			Identifier key = Identifier.tryParse(id);
			if (key != null) BuiltInRegistries.ITEM.getOptional(key).ifPresent(items::add);
		}
		junk = items;
		Map<EntityType<?>, Integer> save = new HashMap<>();
		next.saveLimits.limits.forEach((id, n) -> {
			Identifier key = Identifier.tryParse(id);
			if (key != null) BuiltInRegistries.ENTITY_TYPE.getOptional(key).ifPresent(t -> save.put(t, n));
		});
		saveLimits = save;
		limits = new Cleanup.Limits(next.rules);
		settings = next;
		if (!next.dynamic.enabled) restore(server, "dynamic distances switched off");
	}



	private static String summary(Performance.Settings s) {
		StringBuilder b = new StringBuilder();
		if (s.dynamic.enabled) b.append("dynamic distances, ");
		if (s.activation.enabled) b.append("activation range, ");
		if (s.merge.enabled) b.append("merge radius, ");
		if (s.breeding.enabled) b.append("breeding caps, ");
		if (s.villagers.lobotomize) b.append("villager brains, ");
		if (s.autoCaps.enabled) b.append("auto caps, ");
		if (s.collisions.enabled) b.append("collision cap, ");
		if (s.despawn.enabled) b.append("junk despawn, ");
		if (s.saveLimits.enabled) b.append("save limits, ");
		if (s.ai.enabled) b.append("lighter mob AI, ");
		if (s.hoppers.enabled) b.append("hopper cooldown, ");
		if (s.decor.armorStands) b.append("armor stands still, ");
		if (s.spawning.enabled) b.append("less frequent spawning, ");
		if (s.smooth.saving) b.append("smooth saving, ");
		if (s.smooth.teleports) b.append("warm teleport targets, ");
		if (!s.frozen.isEmpty()) b.append(s.frozen.size()).append(" frozen chunks, ");
		return b.isEmpty() ? "all off" : b.substring(0, b.length() - 2);
	}

	// ── Dynamic distances ──────────────────────────────────────────────

	/** One step lag protection took, undone in reverse order once it's calm. */
	private enum Step { RANGES, MOB_CAP, SIMULATION, TRACKING, VIEW }

	/** What lag protection lowered, newest last. */
	private static final java.util.ArrayDeque<Step> taken = new java.util.ArrayDeque<>();

	/**
	 * The ladder, cheapest for players first: the far-mob ranges (three quick steps), then the mob cap and the
	 * simulation distance taking turns (mob cap first), then how far players see mobs (More steps first), and
	 * last the view distance. Normally a step every 10 seconds, every 5 while ticks take over twice the target.
	 * Going back up undoes the steps in reverse, so the ranges come back last.
	 */
	private static void dynamic(MinecraftServer server) {
		Performance.Dynamic d = settings.dynamic;
		if (!d.enabled) return;
		var list = server.getPlayerList();
		if (baseSim < 0) {
			baseSim = sim = list.getSimulationDistance();
			baseView = view = list.getViewDistance();
			taken.clear();
		}
		// Tick time plus the work between ticks (packets, chunk loading results, mods' tasks): the next tick waits for
		// both, so TPS drops when they add up past the budget even though each tick on its own is quick.
		double ms = server.getAverageTickTimeNanos() / 1_000_000.0;
		eu.explorerseden.nicecontrolcenter.data.History.Recent recent = eu.explorerseden.nicecontrolcenter.data.History.recent(5);
		if (recent.ticks() > 0) ms += recent.betweenBusyNs() / 1e6 / recent.ticks();
		long now = System.currentTimeMillis();
		if (ms > d.targetMspt) {
			calmSince = -1;
			boolean quick = ms > d.targetMspt * 2 || (rangesUsed() && rangeStep < RANGE_STEPS);
			if (now - lastStep < (quick ? 5_000 : 10_000)) return;
			Step next = nextDown(d);
			if (next == null) return;
			String what = switch (next) {
				case RANGES -> {
					rangeStep++;
					yield rangeStep == RANGE_STEPS ? "ranges for far mobs to the lowest set" : "ranges for far mobs (step " + rangeStep + " of " + RANGE_STEPS + ")";
				}
				case MOB_CAP -> {
					mobCap = Math.max(d.mobCapMin, mobCap - 10);
					yield "mob cap " + mobCap + " %";
				}
				case SIMULATION -> {
					sim--;
					list.setSimulationDistance(sim);
					yield "simulation distance " + sim;
				}
				case TRACKING -> {
					tracking = 60;
					yield "range players see mobs from to 60 %";
				}
				case VIEW -> {
					view--;
					list.setViewDistance(view);
					yield "view distance " + view;
				}
			};
			taken.addLast(next);
			step(now, "Lag (" + Math.round(ms) + " ms per tick): lowered the " + what);
			return;
		}
		if (ms >= d.recoverMspt || !lowered()) {
			calmSince = -1;
			return;
		}
		if (calmSince < 0) calmSince = now;
		if (now - calmSince < 60_000 || now - lastStep < 30_000) return;
		Step last = taken.pollLast();
		// Lowered before this list was kept (or switched on in between): the old order, view first.
		if (last == null) last = view < baseView ? Step.VIEW : tracking < 100 ? Step.TRACKING : sim < baseSim ? Step.SIMULATION
				: mobCap < 100 ? Step.MOB_CAP : Step.RANGES;
		String what = switch (last) {
			case VIEW -> {
				view = Math.min(baseView, view + 1);
				list.setViewDistance(view);
				yield "view distance " + view;
			}
			case TRACKING -> {
				tracking = 100;
				yield "range players see mobs from to normal";
			}
			case SIMULATION -> {
				sim = Math.min(baseSim, sim + 1);
				list.setSimulationDistance(sim);
				yield "simulation distance " + sim;
			}
			case MOB_CAP -> {
				mobCap = Math.min(100, mobCap + 10);
				yield "mob cap " + mobCap + " %";
			}
			case RANGES -> {
				rangeStep = Math.max(0, rangeStep - 1);
				yield rangeStep == 0 ? "ranges for far mobs to the whole simulation distance" : "ranges for far mobs (step " + rangeStep + " of " + RANGE_STEPS + ")";
			}
		};
		step(now, "Recovered (" + Math.round(ms) + " ms per tick): raised the " + what + (lowered() ? "" : ", back to normal"));
	}

	/** The next step down, or null when everything is as low as allowed. */
	private static Step nextDown(Performance.Dynamic d) {
		if (rangesUsed() && rangeStep < RANGE_STEPS) return Step.RANGES;
		boolean cap = mobCap > d.mobCapMin;
		boolean simulation = sim > Math.min(d.simulationMin, baseSim);
		if (cap || simulation) {
			// Taking turns, the mob cap first.
			Step before = null;
			for (java.util.Iterator<Step> it = taken.descendingIterator(); it.hasNext();) {
				Step s = it.next();
				if (s == Step.MOB_CAP || s == Step.SIMULATION) {
					before = s;
					break;
				}
			}
			if (cap && (before != Step.MOB_CAP || !simulation)) return Step.MOB_CAP;
			return Step.SIMULATION;
		}
		if (d.extraSteps && tracking > 60) return Step.TRACKING;
		if (d.view && view > Math.min(d.viewMin, baseView)) return Step.VIEW;
		return null;
	}

	private static boolean lowered() {
		return baseSim >= 0 && (sim < baseSim || view < baseView || mobCap < 100 || rangeStep > 0 || tracking < 100);
	}

	private static void step(long now, String text) {
		lastStep = now;
		lastChange = text;
		// Under the panel the alert shows up in its console already; logging it too would show it twice.
		if (PanelBridge.active()) {
			PanelBridge.alert("perf", text);
		} else {
			NiceControlCenter.LOGGER.info(text);
		}
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
		rangeStep = 0;
		tracking = 100;
		taken.clear();
		NiceControlCenter.LOGGER.info("Distances back to normal ({})", why);
		lastChange = "Back to normal (" + why + ")";
		baseSim = baseView = sim = view = -1;
	}

	// ── Spawning ────────────────────────────────────────────────────────

	private static final java.util.Map<ServerLevel, Object[]> spawnLists = new java.util.WeakHashMap<>();

	/**
	 * The mob categories this level tries to spawn this tick: monsters, water mobs and bats only every so many ticks,
	 * each dimension on its own ticks. Worked out once per tick and level (every chunk gets the same list).
	 */
	public static java.util.List<net.minecraft.world.entity.MobCategory> spawnCategories(ServerLevel level, java.util.List<net.minecraft.world.entity.MobCategory> all) {
		Performance.Spawning sp = settings.spawning;
		if (!sp.enabled || all.isEmpty()) return all;
		long time = level.getGameTime();
		Object[] cached = spawnLists.get(level);
		if (cached != null && (long) cached[0] == time && cached[1] == all) {
			@SuppressWarnings("unchecked")
			java.util.List<net.minecraft.world.entity.MobCategory> list = (java.util.List<net.minecraft.world.entity.MobCategory>) cached[2];
			return list;
		}
		long t = time + (level.dimension().identifier().hashCode() & 0xff);
		java.util.List<net.minecraft.world.entity.MobCategory> out = new java.util.ArrayList<>(all.size());
		for (net.minecraft.world.entity.MobCategory c : all) {
			int every = switch (c.getName()) {
				case "monster" -> sp.monstersEvery;
				case "water_creature", "underground_water_creature", "water_ambient", "axolotls" -> sp.waterEvery;
				case "ambient" -> sp.ambientEvery;
				default -> 1;
			};
			if (every <= 1 || t % every == 0) out.add(c);
		}
		spawnLists.put(level, new Object[] {time, all, out});
		return out;
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
		out.put("rangeStep", rangeStep);
		out.put("rangeSteps", RANGE_STEPS);
		out.put("warmTargets", WarmTargets.warm());
		out.put("tracking", tracking);
		long mobs = 0;
		long items = 0;
		for (long[] c : capped) {
			mobs += c[1];
			items += c[2];
		}
		out.put("cappedMobsHour", mobs);
		out.put("cappedItemsHour", items);
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
		if (entity instanceof ArmorStand stand && settings.decor.armorStands) return stillStand(level, stand);
		Performance.Activation a = settings.activation;
		if (!a.enabled || !(entity instanceof Mob mob)) return false;
		// The world's clock, not the entity's: a skipped entity's own tick count doesn't move.
		if ((level.getGameTime() + entity.getId()) % a.every == 0) return false;
		if (immune(mob)) return false;
		int range = rangeOf(entity instanceof Enemy ? a.monstersPercent : entity instanceof AbstractVillager ? a.villagersPercent
				: entity instanceof Animal ? a.animalsPercent : a.otherPercent);
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

	/** An armor stand with nothing happening to it: on the ground, still, not hurt, carrying or carried. It still ticks once a second. */
	private static boolean stillStand(ServerLevel level, ArmorStand stand) {
		if ((level.getGameTime() + stand.getId()) % 20 == 0) return false;
		if (!stand.onGround() || stand.isPassenger() || stand.isVehicle() || stand.isOnFire() || stand.hurtTime > 0) return false;
		// Sideways only: on the ground gravity keeps a small downward speed every tick.
		var motion = stand.getDeltaMovement();
		return motion.x * motion.x + motion.z * motion.z < 1.0E-4;
	}

	/** Whether any player in this level is within range blocks (horizontally). */
	private static boolean playerWithin(ServerLevel level, Entity entity, double range) {
		double[] xz = players.get(level);
		if (xz == null) return true;
		double r2 = range * range;
		for (int i = 0; i < xz.length; i += 2) {
			double dx = xz[i] - entity.getX();
			double dz = xz[i + 1] - entity.getZ();
			if (dx * dx + dz * dz <= r2) return true;
		}
		return false;
	}

	// ── Collisions, despawn, save limits, AI, hoppers, tracking ─────────────

	/** How many others one mob may push per tick; -1 for no limit. */
	public static int collisionMax() {
		return settings.collisions.enabled ? settings.collisions.max : -1;
	}

	/** Ticks after which this dropped item goes; -1 to keep vanilla's 5 minutes. */
	public static int junkTicks(ItemStack stack) {
		Performance.Despawn d = settings.despawn;
		return d.enabled && junk.contains(stack.getItem()) ? d.itemSeconds * 20 : -1;
	}

	/** Ticks a stray arrow (players can't pick it up) stays in the ground; -1 for vanilla. */
	public static int strayArrowTicks() {
		Performance.Despawn d = settings.despawn;
		return d.enabled ? d.arrowSeconds * 20 : -1;
	}

	/** How many of this kind a chunk keeps when it saves; -1 for all. */
	public static int saveLimit(EntityType<?> type) {
		if (!settings.saveLimits.enabled) return -1;
		Integer n = saveLimits.get(type);
		return n == null ? -1 : n;
	}

	public static boolean saveLimitsOn() {
		return settings.saveLimits.enabled && !saveLimits.isEmpty();
	}

	/** Whether this mob re-plans its goals this tick (far from players it only does every few ticks). */
	public static boolean plansGoals(Mob mob) {
		Performance.Ai ai = settings.ai;
		if (!ai.enabled || !(mob.level() instanceof ServerLevel level)) return true;
		if ((level.getGameTime() + mob.getId()) % ai.farGoalEvery == 0) return true;
		if (immune(mob)) return true;
		return playerWithin(level, mob, rangeOf(ai.farPercent));
	}

	/** Whether this mob may search a new path now (at most pathBudget searches per tick). */
	public static boolean mayPathfind(Mob mob) {
		Performance.Ai ai = settings.ai;
		if (!ai.enabled || (ai.whenLagging && !lowered())) return true;
		if (mob instanceof OwnableEntity owned && owned.getOwnerReference() != null) return true;
		if (mob instanceof Raider raider && raider.hasActiveRaid()) return true;
		long now = mob.level().getGameTime();
		if (now != pathsTick) {
			pathsTick = now;
			paths = 0;
		}
		return ++paths <= ai.pathBudget;
	}

	/** Ticks a hopper waits after finding nothing to move; 0 for vanilla (tries again next tick). */
	public static int hopperIdle() {
		return settings.hoppers.enabled ? settings.hoppers.idleCooldown : 0;
	}

	/** How far players see entities, in percent (the lag ladder lowers it). */
	public static int trackingPercent() {
		return tracking;
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
