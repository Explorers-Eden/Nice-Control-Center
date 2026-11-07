package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Runs the mini-game arenas (see {@link Games}): stepping into an entrance, onto its start point or /game join starts a run, an
 * action-bar line shows how it goes, and the end keeps a personal best and fires the Rewards triggers
 * "Game finished" and "New personal best". Templates: infinite parkour (jumps made ahead of you, each player in their
 * own lane), race course (checkpoints in order; running, boat or elytra)
 * and maze (generated; made new daily or after each run). Wave arenas (parties, mobs) run in {@link WaveRuntime}, quick
 * rounds (ghasts, colour floor, anvils, treasure, fishing) in {@link QuickRuntime}, head hunts in {@link HeadHunt}.
 */
public final class GameRuntime {
	public static final String PERMISSION = "nicecontrolcenter.games.admin";

	/** One player's run. */
	static final class Session {
		final UUID player;
		final Arena arena;
		final long started = System.currentTimeMillis();
		/** Parkour: jumps; race: next checkpoint. */
		int progress;
		/** Parkour: its lane and the blocks lying ahead (oldest first); in a box, the way the jumps go (0 +Z, 1 -X, 2 -Z, 3 +X). */
		int lane = -1;
		int heading;
		/** Parkour: what the last jump is (0 a block, 1 a slab, 2 a thin block). */
		int lastKind;
		final Deque<BlockPos> blocks = new ArrayDeque<>();
		BlockPos lastBlock;

		Session(UUID player, Arena arena) {
			this.player = player;
			this.arena = arena;
		}

		long millis() {
			return System.currentTimeMillis() - started;
		}
	}

	private static volatile Games.Settings settings = new Games.Settings().fill();
	private static long modified = -1;
	private static final Map<UUID, Session> sessions = new HashMap<>();
	/** After a run ends, the start point waits a moment before starting the next one. */
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	/** On bosses summoned with /game boss. */
	private static final String BOSS_TRY = "ncc_boss_try";
	/** The wait after a round or run (and after leaving a pad or queue) before a pad takes the player again: the same for every game. */
	public static final long WAIT_AFTER = 1000;
	private static Map<String, Map<String, Games.Score>> scores = new LinkedHashMap<>();
	private static Path scoresFile;
	/** Parkour blocks placed and not yet removed (cleared on start-up after a crash): "dim|x|y|z". */
	private static final Set<String> placed = new java.util.LinkedHashSet<>();
	private static Path placedFile;
	private static boolean placedDirty;
	/** Maze arenas: the day they were last built. */
	private static final Map<String, Long> mazeDay = new HashMap<>();
	private static final Random random = new Random();

	private GameRuntime() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Games.FILE);
	}

	public static Games.Settings settings() {
		return settings;
	}

	/** Whether the hub spawn takes this player back (set, and they're in a game area or the hub's dimension). */
	public static boolean hubCovers(ServerPlayer p) {
		Games.Pos hub = settings.hub;
		return hub != null && (dim(p).equals(hub.dim) || eu.explorerseden.nicecontrolcenter.claims.GameClaims.inGame(p));
	}

	/** To the hub spawn; false when there's none (or its dimension is gone). */
	public static boolean toHub(MinecraftServer server, ServerPlayer p) {
		Games.Pos hub = settings.hub;
		ServerLevel level = hub == null ? null : level(server, hub.dim);
		if (level == null) return false;
		p.teleportTo(level, hub.x, hub.y, hub.z, Set.of(), hub.yaw, 0, true);
		p.resetFallDistance();
		return true;
	}

	/** Respawned players to check again: when something else moved them after the hub teleport, they go back. */
	private static final Map<UUID, Long> hubCheck = new HashMap<>();

	/**
	 * After a death that the hub spawn covers: to the hub spawn now, and once more a few ticks later if they aren't
	 * there (another mod, a datapack or the respawn itself moved them on).
	 */
	public static void toHubAfterRespawn(MinecraftServer server, ServerPlayer p) {
		boolean ok = toHub(server, p);
		Games.Pos hub = settings.hub;
		NiceControlCenter.LOGGER.info("Games: {} respawned: {}", p.getGameProfile().name(),
				ok ? "to the hub spawn in " + hub.dim : "no hub spawn to go to (none set, or its dimension is missing)");
		if (ok) hubCheck.put(p.getUUID(), (long) server.getTickCount() + 5);
	}

	private static void checkHub(MinecraftServer server) {
		if (hubCheck.isEmpty()) return;
		long now = server.getTickCount();
		for (Map.Entry<UUID, Long> e : List.copyOf(hubCheck.entrySet())) {
			if (e.getValue() > now) continue;
			hubCheck.remove(e.getKey());
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Games.Pos hub = settings.hub;
			if (p == null || hub == null || !p.isAlive()) continue;
			boolean there = dim(p).equals(hub.dim) && p.distanceToSqr(hub.x, hub.y, hub.z) <= 4 * 4;
			if (there) continue;
			NiceControlCenter.LOGGER.info("Games: {} was moved away from the hub spawn after respawning (now in {} at {} {} {}): sent back", p.getGameProfile().name(), dim(p),
					Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()));
			toHub(server, p);
		}
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			scoresFile = server.getWorldPath(LevelResource.ROOT).resolve(Games.SCORES);
			scores = Games.readScores(scoresFile);
			placedFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-games-blocks.json");
			mazesFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-mazes.json");
			mazesBuilt = readMazes(mazesFile);
			LoopSound.exportSongs(server);
			clearLeftovers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Session s : List.copyOf(sessions.values())) end(server, s, null, false);
			savePlaced();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) reload();
			if (server.getTickCount() % 2 == 0) tick(server);
			checkHub(server);
			stepMazes();
			if (server.getTickCount() % 60 == 0) mazeMobs(server);
			if (server.getTickCount() % 1200 == 0) dailyMazes(server);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p && sessions.containsKey(p.getUUID())) end(p.level().getServer(), sessions.get(p.getUUID()), "You died: run over.", false);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Session s = sessions.get(handler.player.getUUID());
			if (s != null) end(server, s, null, false);
		});
	}

	public static void reload() {
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		settings = Games.read(file());
	}

	/** reload() with the file's time read right now (commands: a panel save a second ago counts). */
	static void reloadNow() {
		eu.explorerseden.nicecontrolcenter.core.FileWatch.now(file());
		reload();
	}

	static void saveSettings() {
		try {
			Games.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Games: could not save the games: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		LoopSound.tick(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Session s = sessions.get(p.getUUID());
			if (s == null) {
				if (!settings.enabled || cooldown.getOrDefault(p.getUUID(), 0L) > now || QuickRuntime.inGame(p) || WaveRuntime.inGame(p)) continue;
				Arena a = onStartPad(p);
				if (a != null) start(server, p, a);
				continue;
			}
			// Another dimension (a teleport out): the run is over.
			if (!dim(p).equals(startDim(s.arena))) {
				end(server, s, "You left the arena: run over.", false);
				continue;
			}
			switch (s.arena.type) {
				case "parkour" -> parkour(server, p, s);
				case "race" -> race(server, p, s);
				case "maze" -> maze(server, p, s);
				default -> {
				}
			}
			if (sessions.get(p.getUUID()) == s && server.getTickCount() % 10 == 0) hud(p, s);
		}
		if (placedDirty && server.getTickCount() % 200 == 0) savePlaced();
	}

	private static Arena onStartPad(ServerPlayer p) {
		String dim = dim(p);
		for (Arena a : settings.arenas) {
			if (!a.enabled || a.start == null || !a.start.dim.equals(dim) || a.type.equals("waves") || Games.quick(a.type) || a.type.equals("heads")) continue;
			double dx = p.getX() - a.start.x, dz = p.getZ() - a.start.z, dy = p.getY() - a.start.y;
			if (dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5) return a;
		}
		return null;
	}

	private static void hud(ServerPlayer p, Session s) {
		String line = switch (s.arena.type) {
			case "parkour" -> "Jumps: " + s.progress + " · level " + (parkourLevel(s.arena, s.progress) + 1) + best(s, " · best ");
			case "race" -> clock(s.millis()) + " · checkpoint " + Math.min(s.progress, s.arena.checkpoints.size()) + "/" + s.arena.checkpoints.size() + best(s, " · best ");
			default -> clock(s.millis()) + best(s, " · best ");
		};
		p.sendOverlayMessage(Words.lit(s.arena.shown() + " · " + line).withStyle(st -> st.withColor(s.arena.rgb())));
	}

	private static String best(Session s, String lead) {
		Games.Score sc = scores.getOrDefault(s.arena.id, Map.of()).get(s.player.toString());
		if (sc == null) return "";
		return lead + (Games.higherIsBetter(s.arena.type) ? String.valueOf(sc.best) : clock(sc.best));
	}

	// ---------------------------------------------------------------- start and end

	static String start(MinecraftServer server, ServerPlayer p, Arena a) {
		if (sessions.containsKey(p.getUUID())) return "You're already in a game: /game leave first.";
		ServerLevel level = level(server, a.start.dim);
		if (level == null) return "That arena's dimension isn't there.";
		freshArea(level, a);
		Session s = new Session(p.getUUID(), a);
		switch (a.type) {
			case "parkour" -> {
				int lane = freeLane(a);
				if (lane < 0) return "All " + laneCount(a) + " lanes are in use; try again in a moment.";
				s.lane = lane;
				sessions.put(p.getUUID(), s);
				BlockPos first = laneStart(a, lane);
				place(level, s, first, a);
				for (int i = 0; i < a.ahead; i++) nextJump(level, s, a);
				p.teleportTo(level, first.getX() + 0.5, first.getY() + 1, first.getZ() + 0.5, Set.of(), 0, 0, true);
			}
			case "race" -> {
				if (a.checkpoints.isEmpty() && a.finish == null) return "This race has no checkpoints or finish yet.";
				sessions.put(p.getUUID(), s);
			}
			case "maze" -> {
				if (a.corner == null) return "This maze has no corner yet.";
				if (mazeBuilding(a)) return "The maze is being built; try again in a few seconds.";
				sessions.put(p.getUUID(), s);
				double in = Math.floor(a.corner.x) + 1 + a.mazePath / 2.0, inZ = Math.floor(a.corner.z) + 1 + a.mazePath / 2.0;
				p.teleportTo(level, in, a.corner.y + 1, inZ, Set.of(), 0, 0, true);
			}
			case "waves" -> {
				return "Wave arenas start from their pad (with your party).";
			}
			default -> {
				return "Unknown game.";
			}
		}
		Cleanup.refresh(p);
		level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
		String hint = switch (a.type) {
			case "parkour" -> "Jump as far as you can!";
			case "race" -> "Pass the " + a.checkpoints.size() + " checkpoints in order, then the finish." + (a.timeLimit > 0 ? " You have " + clock(a.timeLimit * 1000L) + "." : "");
			default -> "Find the way to the glowing corner.";
		};
		p.sendSystemMessage(Words.lit(a.startMessage.isBlank() ? "▶ " + a.shown() + " started. " + hint + " /game leave stops." : fill(a.startMessage, a, p, "")).withStyle(ChatFormatting.GREEN));
		commands(server, a.startCommands, a, p, "");
		return null;
	}

	/** Ends a run: finished = it counts (score kept, rewards). */
	static void end(MinecraftServer server, Session s, String why, boolean finished) {
		sessions.remove(s.player);
		cooldown.put(s.player, System.currentTimeMillis() + WAIT_AFTER);
		ServerPlayer p = server.getPlayerList().getPlayer(s.player);
		ServerLevel level = level(server, s.arena.start.dim);
		if (level != null) for (BlockPos b : s.blocks) unplace(level, b);
		s.blocks.clear();
		if (s.arena.type.equals("parkour") && s.progress > 0) finished = true;
		long score = Games.higherIsBetter(s.arena.type) ? s.progress : s.millis();
		boolean record = false;
		if (finished && p != null) record = recordScore(s.arena, p, score);
		if (p != null) {
			String result = Games.higherIsBetter(s.arena.type) ? s.progress + " jump" + (s.progress == 1 ? "" : "s") : clock(score);
			if (finished) {
				String line = s.arena.finishMessage.isBlank() ? "■ " + s.arena.shown() + ": " + result : fill(s.arena.finishMessage, s.arena, p, result);
				p.sendSystemMessage(Words.lit(line + (record ? " · new personal best!" : "")).withStyle(record ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
				commands(server, s.arena.finishCommands, s.arena, p, result);
			}
			else if (why != null) p.sendSystemMessage(Words.lit(why).withStyle(ChatFormatting.GRAY));
			// To the game hub spawn, else the start point (not after dying: the respawn does that; not when they left for another dimension).
			if (p.isAlive() && (why == null || !why.startsWith("You left the arena"))) {
				if (!Portals.returnTo(server, p, s.arena) && level != null) p.teleportTo(level, s.arena.start.x, freeY(level, p, s.arena.start.x, s.arena.start.y, s.arena.start.z), s.arena.start.z, Set.of(), s.arena.start.yaw, 0, true);
				cooldown.put(s.player, System.currentTimeMillis() + WAIT_AFTER);
			}
			if (finished) {
				RewardsControl.fireGame(server, "gameFinished", p, s.arena.name, score, !Games.higherIsBetter(s.arena.type));
				points(p, s.arena, score, !Games.higherIsBetter(s.arena.type));
				if (record) RewardsControl.fireGame(server, "gameRecord", p, s.arena.name, score, !Games.higherIsBetter(s.arena.type));
			}
		}
		// The game as it's set now (the panel may have changed it during the run, e.g. a new size), not as it was at the start.
		reloadNow();
		Arena now = settings.arenas.stream().filter(x -> x.id.equals(s.arena.id)).findFirst().orElse(s.arena);
		if (now.type.equals("maze") && finished && now.regenerate.equals("run") && level != null && sessions.values().stream().noneMatch(o -> o.arena.id.equals(now.id))) buildMaze(level, now);
		// However it ended (finished, left, died, out of time): with nobody else in it, what lies around goes.
		else if (level != null) freshArea(level, now);
	}

	// ---------------------------------------------------------------- parkour

	private static int freeLane(Arena a) {
		int lanes = laneCount(a);
		boolean[] used = new boolean[lanes];
		for (Session s : sessions.values()) if (s.arena.id.equals(a.id) && s.lane >= 0 && s.lane < lanes) used[s.lane] = true;
		for (int i = 0; i < lanes; i++) if (!used[i]) return i;
		return -1;
	}

	/** The maze and a little around it (null without a corner). */
	static AABB mazeArea(Arena a) {
		if (a.corner == null) return null;
		double x = Math.floor(a.corner.x), y = Math.floor(a.corner.y), z = Math.floor(a.corner.z);
		int w = a.mazeBlocks();
		return new AABB(x - 2, y - 2, z - 2, x + w + 2, y + 12, z + w + 2);
	}

	/** A run starting with nobody else in the game: what earlier runs left lying around goes (maze, parkour box). */
	private static void freshArea(ServerLevel level, Arena a) {
		if (sessions.values().stream().anyMatch(o -> o.arena.id.equals(a.id))) return;
		AABB box = a.type.equals("maze") ? mazeArea(a) : null;
		int[] pb = a.type.equals("parkour") ? parkourBox(a) : null;
		if (pb != null) box = new AABB(pb[0] - 2, pb[1] - 2, pb[2] - 2, pb[3] + 3, pb[4] + 8, pb[5] + 3);
		Cleanup.items(level, box);
	}

	/** The parkour box, when its corners are set (same dimension as the start). */
	private static int[] parkourBox(Arena a) {
		if (a.areaMin == null || a.areaMax == null || a.start == null || !a.areaMin.dim.equals(a.start.dim)) return null;
		return new int[] { (int) Math.min(a.areaMin.x, a.areaMax.x), (int) Math.min(a.areaMin.y, a.areaMax.y), (int) Math.min(a.areaMin.z, a.areaMax.z),
				(int) Math.max(a.areaMin.x, a.areaMax.x), (int) Math.max(a.areaMin.y, a.areaMax.y), (int) Math.max(a.areaMin.z, a.areaMax.z) };
	}

	/** Lanes at once: as set, but in a box only as many as get 9 blocks each (side by side along X). */
	private static int laneCount(Arena a) {
		int[] b = parkourBox(a);
		return b == null ? a.lanes : Math.max(1, Math.min(a.lanes, (b[3] - b[0] + 1) / 9));
	}

	/** A lane's own part of the box: x from, x to (the whole box's z and y). */
	private static int[] laneStrip(Arena a, int lane) {
		int[] b = parkourBox(a);
		int width = (b[3] - b[0] + 1) / laneCount(a);
		return new int[] { b[0] + lane * width, b[0] + (lane + 1) * width - 1 };
	}

	/** Lanes run side by side along X, each laneSpacing apart, starting a lane beyond the start point. */
	private static BlockPos laneStart(Arena a, int lane) {
		int[] b = parkourBox(a);
		if (b != null) {
			// In a box: at the near (north) edge of the lane's strip, at the start point's height (kept inside).
			int[] strip = laneStrip(a, lane);
			int y = (int) Math.max(b[1] + 1, Math.min(b[4] - 3, a.start.y));
			return new BlockPos((strip[0] + strip[1]) / 2, y, b[2] + 1);
		}
		return BlockPos.containing(a.start.x + (lane + 1) * a.laneSpacing, a.start.y, a.start.z);
	}

	/** Parkour presets: a new level every … jumps, the first level, the highest. */
	private static int[] preset(Arena a) {
		return switch (a.difficulty) {
			case "easy" -> new int[] { 25, 0, 2 };
			case "hard" -> new int[] { 10, 1, 5 };
			case "extreme" -> new int[] { 8, 2, 6 };
			default -> new int[] { 15, 0, 4 };
		};
	}

	/** The level a parkour run is at after this many jumps. */
	static int parkourLevel(Arena a, int jumps) {
		int[] p = preset(a);
		return Math.min(p[2], p[1] + jumps / p[0]);
	}

	/** One jump: how far ahead, up or down, to the side, and what it lands on (0 a block, 1 a slab, 2 a thin block). */
	private record Jump(int forward, int dy, int side, int kind) {
	}

	/**
	 * A jump for the level, always one a sprinting player can make: up one only onto a block or a slab and at most 3
	 * ahead, never up from a slab or onto a thin block, thin blocks and slabs at most 3 ahead, 5 ahead only straight
	 * and one down at the highest levels; never past the arena's longest gap.
	 */
	private static Jump jump(Arena a, int lvl, int lastKind) {
		double r = random.nextDouble();
		int kind = lvl >= 4 && r < 0.25 ? 2 : lvl >= 3 && r < 0.55 ? 1 : 0;
		int dy = lvl == 0 ? (random.nextInt(5) == 0 ? -1 : 0) : random.nextInt(3) - 1;
		if (lastKind == 1 || kind == 2) dy = Math.min(dy, 0);
		int side = lvl == 0 ? 0 : random.nextInt(3) - 1;
		int max = dy > 0 || lastKind != 0 || kind != 0 ? 3 : 4;
		if (lvl >= 6 && dy < 0 && side == 0 && kind == 0 && lastKind == 0) max = 5;
		int min = lvl >= 5 ? 3 : 2;
		int forward = lvl <= 1 ? 2 + random.nextInt(2) : min + random.nextInt(Math.max(1, max - min + 1));
		forward = Math.max(2, Math.min(Math.min(forward, max), a.maxGap));
		return new Jump(forward, dy, side, kind);
	}

	private static net.minecraft.world.level.block.state.BlockState jumpBlock(Arena a, int kind) {
		return switch (kind) {
			case 1 -> Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
			case 2 -> (random.nextBoolean() ? Blocks.GLASS_PANE : Blocks.IRON_BARS).defaultBlockState();
			default -> {
				List<Block> palette = eu.explorerseden.nicecontrolcenter.core.BlockLists.of(a.palette, Blocks.STONE);
				yield palette.get(random.nextInt(palette.size())).defaultBlockState();
			}
		};
	}

	/** Free to place a jump: the spot and two above it are air, and none of the jumps ahead is in the way. */
	private static boolean free(ServerLevel level, Session s, BlockPos at) {
		for (int up = 0; up < 3; up++) if (!level.getBlockState(at.above(up)).isAir()) return false;
		for (BlockPos b : s.blocks) if (Math.abs(b.getX() - at.getX()) <= 1 && Math.abs(b.getZ() - at.getZ()) <= 1 && Math.abs(b.getY() - at.getY()) <= 2) return false;
		return true;
	}

	private static void nextJump(ServerLevel level, Session s, Arena a) {
		BlockPos last = s.lastBlock;
		int lvl = parkourLevel(a, s.progress);
		int[] box = parkourBox(a);
		for (int attempt = 0; attempt < 12; attempt++) {
			Jump j = jump(a, attempt < 8 ? lvl : 0, s.lastKind);
			if (box != null) {
				// In a box: on along the heading within the lane's strip (and the box's height), turning at a wall.
				int[] strip = laneStrip(a, s.lane);
				int y = Math.max(box[1] + 1, Math.min(box[4] - 3, last.getY() + j.dy()));
				if (attempt == 0 && random.nextInt(8) == 0) s.heading = (s.heading + (random.nextBoolean() ? 1 : 3)) % 4;
				for (int turn = 0; turn < 4; turn++) {
					int h = (s.heading + turn) % 4;
					int dx = h == 1 ? -j.forward() : h == 3 ? j.forward() : j.side(), dz = h == 0 ? j.forward() : h == 2 ? -j.forward() : j.side();
					BlockPos next = new BlockPos(last.getX() + dx, y, last.getZ() + dz);
					if (next.getX() < strip[0] + 1 || next.getX() > strip[1] - 1 || next.getZ() < box[2] + 1 || next.getZ() > box[5] - 1 || !free(level, s, next)) continue;
					s.heading = h;
					place(level, s, next, a, j.kind());
					return;
				}
				continue;
			}
			BlockPos lane = laneStart(a, s.lane);
			// Keep within the lane (laneSpacing wide) and within the world's height.
			int x = Math.max(lane.getX() - a.laneSpacing / 3, Math.min(lane.getX() + a.laneSpacing / 3, last.getX() + j.side()));
			int y = Math.max(level.getMinY() + 4, Math.min(level.getMaxY() - 8, last.getY() + j.dy()));
			BlockPos next = new BlockPos(x, y, last.getZ() + j.forward());
			if (!free(level, s, next)) continue;
			place(level, s, next, a, j.kind());
			return;
		}
		// Hemmed in: a plain short jump (two ahead, same height) toward the middle of the box, or straight on.
		if (box != null) {
			int[] strip = laneStrip(a, s.lane);
			int mx = (strip[0] + strip[1]) / 2, mz = (box[2] + box[5]) / 2;
			place(level, s, last.offset(Integer.signum(mx - last.getX()) * 2, 0, Integer.signum(mz - last.getZ()) * 2), a, 0);
		} else {
			place(level, s, last.offset(0, 0, 2), a, 0);
		}
	}

	private static void place(ServerLevel level, Session s, BlockPos pos, Arena a) {
		place(level, s, pos, a, 0);
	}

	private static void place(ServerLevel level, Session s, BlockPos pos, Arena a, int kind) {
		if (!level.getBlockState(pos).isAir()) pos = pos.above();
		level.setBlock(pos, jumpBlock(a, kind), 3);
		s.blocks.addLast(pos);
		s.lastBlock = pos;
		s.lastKind = kind;
		placed.add(level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ());
		placedDirty = true;
	}

	private static void unplace(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		placed.remove(level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ());
		placedDirty = true;
	}

	/** A block a quick round put up (a mole, a target): back to air on the next start if the server stops first. */
	static void track(ServerLevel level, BlockPos pos) {
		placed.add(level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ());
		placedDirty = true;
	}

	/** The height, or higher when that's inside a block (a grid built where the spawn is): the first free spot, up to 8 blocks up. */
	public static double freeY(ServerLevel level, ServerPlayer p, double x, double y, double z) {
		for (int up = 0; up <= 8; up++) {
			if (level.noCollision(p, p.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(x, y + up, z))) return y + up;
		}
		return y;
	}

	/** Takes a tracked block away (air). */
	static void untrack(ServerLevel level, BlockPos pos) {
		unplace(level, pos);
	}

	/** Rising lava: its box, so lava and fire left in it by a crash go on the next start. */
	static String trackLava(ServerLevel level, net.minecraft.world.phys.AABB box) {
		String key = "lava|" + level.dimension().identifier() + "|" + (int) Math.floor(box.minX) + "|" + (int) Math.floor(box.minY) + "|" + (int) Math.floor(box.minZ)
				+ "|" + ((int) Math.ceil(box.maxX) - 1) + "|" + ((int) Math.ceil(box.maxY) - 1) + "|" + ((int) Math.ceil(box.maxZ) - 1);
		if (placed.add(key)) savePlaced();
		return key;
	}

	static void untrackLava(String key) {
		if (key != null && placed.remove(key)) placedDirty = true;
	}

	private static void parkour(MinecraftServer server, ServerPlayer p, Session s) {
		ServerLevel level = (ServerLevel) p.level();
		// Below the lowest block ahead: fell.
		int lowest = s.blocks.stream().mapToInt(BlockPos::getY).min().orElse(p.getBlockY());
		if (p.getY() < lowest - 3) {
			end(server, s, "You fell.", true);
			return;
		}
		if (!p.onGround()) return;
		BlockPos under = p.getOnPos();
		// Landed on one of the blocks ahead: each block passed is a jump.
		int i = 0;
		for (BlockPos b : s.blocks) {
			if (b.equals(under) && i > 0) {
				for (int k = 0; k < i; k++) {
					unplace(level, s.blocks.removeFirst());
					s.progress++;
					nextJump(level, s, s.arena);
					milestone(server, p, s);
				}
				level.playSound(null, under, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6f, 1.2f + Math.min(0.8f, s.progress / 50f));
				return;
			}
			i++;
		}
	}

	/** Every few jumps: the arena's reward commands and the Rewards trigger "Makes jumps in parkour". */
	private static void milestone(MinecraftServer server, ServerPlayer p, Session s) {
		Arena a = s.arena;
		String jumps = String.valueOf(s.progress);
		int lvl = parkourLevel(a, s.progress);
		if (lvl > parkourLevel(a, s.progress - 1)) {
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Words.lit("Level " + (lvl + 1)).withStyle(ChatFormatting.GOLD)));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Words.lit(switch (lvl) {
				case 1 -> "up, down and sideways";
				case 2 -> "longer jumps";
				case 3 -> "slabs";
				case 4 -> "thin blocks";
				case 5 -> "far and high";
				default -> "the longest jumps";
			}).withStyle(ChatFormatting.GRAY)));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.0f);
		}
		if (a.everyJumps > 0 && s.progress % a.everyJumps == 0) {
			p.sendOverlayMessage(Words.lit("★ " + s.progress + " jumps!").withStyle(ChatFormatting.GOLD));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
			for (String line : a.everyCommands) {
				String cmd = fill(Games.math(line.strip().replaceFirst("^/", ""), Map.of("jumps", jumps)), a, p, jumps).replace("{jumps}", jumps);
				if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
			}
		}
		RewardsControl.fireEvery(server, "parkourJumps", p, a.name, s.progress);
	}

	// ---------------------------------------------------------------- race, maze

	private static boolean inside(ServerPlayer p, Games.Point pt) {
		double dx = p.getX() - pt.x, dy = p.getY() - pt.y, dz = p.getZ() - pt.z;
		return dx * dx + dy * dy + dz * dz <= pt.radius * pt.radius;
	}

	private static void race(MinecraftServer server, ServerPlayer p, Session s) {
		if (s.arena.timeLimit > 0) Countdown.tick("race:" + s.player, s.arena.timeLimit * 1000L - s.millis(), List.of(p));
		if (s.arena.timeLimit > 0 && s.millis() > s.arena.timeLimit * 1000L) {
			end(server, s, "Time's up: run over.", false);
			return;
		}
		List<Games.Point> cps = s.arena.checkpoints;
		if (s.progress < cps.size()) {
			if (inside(p, cps.get(s.progress))) {
				s.progress++;
				p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.7f, 1.0f + s.progress * 0.05f);
				// The arena's commands at each checkpoint ({checkpoint}).
				String cp = String.valueOf(s.progress);
				for (String line : s.arena.checkpointCommands) {
					String cmd = fill(Games.math(line.strip().replaceFirst("^/", ""), Map.of("checkpoint", cp)), s.arena, p, cp).replace("{checkpoint}", cp);
					if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
				}
			}
			return;
		}
		if (s.arena.finish == null || inside(p, s.arena.finish)) end(server, s, null, true);
	}

	private static void maze(MinecraftServer server, ServerPlayer p, Session s) {
		Arena a = s.arena;
		int from = mazeCell(a, a.size - 1);
		double x = p.getX() - Math.floor(a.corner.x), z = p.getZ() - Math.floor(a.corner.z);
		if (x >= from && x < from + a.mazePath && z >= from && z < from + a.mazePath && Math.abs(p.getY() - (a.corner.y + 1)) < 2) end(server, s, null, true);
	}

	/** Maze: where cell i (0 … size-1) starts, in blocks from the corner (it's mazePath wide). */
	static int mazeCell(Arena a, int i) {
		return i * (a.mazePath + 1) + 1;
	}

	/** Maze: the middle of the goal, on its floor (null without a corner). */
	public static Games.Pos mazeGoal(Arena a) {
		if (a.corner == null) return null;
		double c = mazeCell(a, a.size - 1) + a.mazePath / 2.0;
		return new Games.Pos(a.corner.dim, Math.floor(a.corner.x) + c, Math.floor(a.corner.y) + 1, Math.floor(a.corner.z) + c, 0);
	}

	/**
	 * Maze mobs, every 3 s while someone runs it: up to maxAlive from the pool roam, each appearing in a cell 12–32 blocks from
	 * a runner (none closer than 12 to anyone), so they come out of the dark rather than next to you. Gone when
	 * nobody runs it.
	 */
	private static void mazeMobs(MinecraftServer server) {
		for (Arena a : settings.arenas) {
			if (!a.type.equals("maze") || a.corner == null) continue;
			ServerLevel level = level(server, a.corner.dim);
			if (level == null) continue;
			String tag = "ncc_maze_" + a.id;
			List<ServerPlayer> runners = new ArrayList<>();
			for (Session s : sessions.values()) {
				if (!s.arena.id.equals(a.id)) continue;
				ServerPlayer p = server.getPlayerList().getPlayer(s.player);
				if (p != null && p.level() == level) runners.add(p);
			}
			int w = a.mazeBlocks();
			AABB box = new AABB(Math.floor(a.corner.x), a.corner.y - 2, Math.floor(a.corner.z), Math.floor(a.corner.x) + w, a.corner.y + 12, Math.floor(a.corner.z) + w);
			List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class, box.inflate(16), e -> e.isAlive() && e.entityTags().contains(tag));
			if (runners.isEmpty() || a.pool.isEmpty()) {
				mobs.forEach(Entity::discard);
				continue;
			}
			for (LivingEntity m : mobs) {
				if (m instanceof net.minecraft.world.entity.Mob mob && (a.peacefulFight || Aggro.landShy(mob))) {
					Aggro.make(mob, 3f);
					if (mob.getTarget() == null) mob.setTarget(runners.get(random.nextInt(runners.size())));
				}
			}
			if (mobs.size() >= a.maxAlive || mazeBuilding(a)) continue;
			List<MobLines.Entry> pool = a.pool.stream().map(MobLines::parse).filter(e -> e != null).toList();
			if (pool.isEmpty()) continue;
			ServerPlayer near = runners.get(random.nextInt(runners.size()));
			for (int tries = 0; tries < 24; tries++) {
				double x = Math.floor(a.corner.x) + mazeCell(a, random.nextInt(a.size)) + a.mazePath / 2.0;
				double z = Math.floor(a.corner.z) + mazeCell(a, random.nextInt(a.size)) + a.mazePath / 2.0;
				double d = Math.hypot(near.getX() - x, near.getZ() - z);
				if (d < 12 || d > 32 || runners.stream().anyMatch(r -> Math.hypot(r.getX() - x, r.getZ() - z) < 12)) continue;
				MobLines.Entry e = pool.get(random.nextInt(pool.size()));
				MobLines.summon(server, a.corner.dim, x, Math.floor(a.corner.y) + 1, z, new MobLines.Entry(e.id(), 1, e.nbt()), "Tags:[\"" + tag + "\"]");
				break;
			}
		}
	}

	/**
	 * A maze of size × size cells (each mazePath blocks wide, walls of one block between), path from the corner
	 * to the far corner; optionally a ceiling, chests in some dead ends.
	 */
	/** Maze: the open ways out of a cell. */
	private static int mazeWays(boolean[][] open, int x, int z) {
		return (open[x + 1][z] ? 1 : 0) + (open[x - 1][z] ? 1 : 0) + (open[x][z + 1] ? 1 : 0) + (open[x][z - 1] ? 1 : 0);
	}

	/** Maze: a cell next to one of the two a wall joins, open to it, already branches three ways. */
	private static boolean mazeNearJunction(boolean[][] open, int x, int z, int dx, int dz) {
		int w = open.length;
		for (int side = -1; side <= 1; side += 2) {
			int cx = x + dx * side, cz = z + dz * side;
			for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
				int nx = cx + 2 * d[0], nz = cz + 2 * d[1];
				if (nx <= 0 || nz <= 0 || nx >= w - 1 || nz >= w - 1 || !open[cx + d[0]][cz + d[1]]) continue;
				if (mazeWays(open, nx, nz) >= 3) return true;
			}
		}
		return false;
	}

	/** Maze: the most wall blocks a free-standing piece may have before it counts as big enough. */
	private static final int MAZE_ISLAND = 9;

	/** Maze: the wall at this post still reaches the outer wall, or is a big enough piece of its own. */
	private static boolean mazeWallHolds(boolean[][] open, int w, int x, int z) {
		if (open[x][z]) return true;
		Deque<int[]> todo = new ArrayDeque<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		todo.push(new int[] { x, z });
		seen.add((long) x * w + z);
		while (!todo.isEmpty()) {
			int[] c = todo.pop();
			if (c[0] == 0 || c[1] == 0 || c[0] == w - 1 || c[1] == w - 1 || seen.size() >= MAZE_ISLAND) return true;
			for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
				int px = c[0] + d[0], pz = c[1] + d[1];
				if (!open[px][pz] && seen.add((long) px * w + pz)) todo.push(new int[] { px, pz });
			}
		}
		return false;
	}

	/** Maze: the walls still joined to a post (the outer wall counts). */
	private static int mazeWalls(boolean[][] open, int w, int x, int z) {
		int n = 0;
		for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
			int px = x + d[0], pz = z + d[1];
			if (px >= 0 && pz >= 0 && px < w && pz < w && !open[px][pz]) n++;
		}
		return n;
	}

	/**
	 * Building a maze: mobs, vehicles, items and the like in it go (not players, NPCs, pets or named mobs); players in
	 * it go to its start so nobody ends up in a wall.
	 */
	private static void clearMazeEntities(ServerLevel level, Arena a, boolean movePlayers) {
		AABB box = mazeArea(a);
		if (box == null) return;
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, Entity::isAlive)) {
			if (e instanceof ServerPlayer p) {
				if (!movePlayers) continue;
				double in = Math.floor(a.corner.x) + 1 + a.mazePath / 2.0, inZ = Math.floor(a.corner.z) + 1 + a.mazePath / 2.0;
				p.teleportTo(level, in, a.corner.y + 1, inZ, Set.of(), p.getYRot(), p.getXRot(), true);
				continue;
			}
			if (e.hasCustomName() || e.isPassenger() && e.getVehicle() instanceof ServerPlayer) continue;
			// Decorations, labels and markers stay (entrance labels, claim titles, …).
			if (e instanceof net.minecraft.world.entity.decoration.ArmorStand || e instanceof net.minecraft.world.entity.decoration.HangingEntity
					|| e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.Interaction || e instanceof net.minecraft.world.entity.Marker) continue;
			if (e.entityTags().stream().anyMatch(t -> t.startsWith("ncc_npc") || t.startsWith("ncc_shop") || t.startsWith("nicecontrolcenter_"))) continue;
			if (e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerReference() != null) continue;
			if (e.hasExactlyOnePlayerPassenger() || e.getPassengers().stream().anyMatch(x -> x instanceof ServerPlayer)) continue;
			e.discard();
		}
	}

	/** Maze: the most cells a corridor goes straight while it could turn. */
	private static final int MAZE_STRAIGHT = 3;

	static void buildMaze(ServerLevel level, Arena a) {
		int n = a.size;
		int w = n * 2 + 1;
		BlockPos o = BlockPos.containing(a.corner.x, a.corner.y, a.corner.z);
		boolean[][] open = new boolean[w][w];
		boolean[][] seen = new boolean[n][n];
		// Cells as {x, z, the way it came: dx, dz, cells gone straight that way}.
		Deque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[] { 0, 0, 0, 0, 0 });
		seen[0][0] = true;
		open[1][1] = true;
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		// A turn is three times as likely as going on straight, and no more than MAZE_STRAIGHT cells straight while a
		// turn is possible: no long straight corridors.
		int straightWeight = 1, turnWeight = 3;
		while (!stack.isEmpty()) {
			int[] c = stack.peek();
			List<int[]> next = new ArrayList<>();
			boolean canTurn = false;
			for (int[] d : dirs) {
				int x = c[0] + d[0], z = c[1] + d[1];
				if (x < 0 || z < 0 || x >= n || z >= n || seen[x][z]) continue;
				boolean straight = d[0] == c[2] && d[1] == c[3];
				canTurn |= !straight;
				next.add(new int[] { x, z, d[0], d[1], straight ? straightWeight : turnWeight, straight ? c[4] + 1 : 1 });
			}
			if (next.isEmpty()) {
				stack.pop();
				continue;
			}
			if (canTurn) next.removeIf(t -> t[5] > MAZE_STRAIGHT);
			int total = 0;
			for (int[] t : next) total += t[4];
			int pick = random.nextInt(total);
			int[] to = next.get(next.size() - 1);
			for (int[] t : next) {
				if ((pick -= t[4]) < 0) {
					to = t;
					break;
				}
			}
			seen[to[0]][to[1]] = true;
			open[c[0] + to[0] + 1][c[1] + to[1] + 1] = true;
			open[to[0] * 2 + 1][to[1] * 2 + 1] = true;
			stack.push(new int[] { to[0], to[1], to[2], to[3], to[5] });
		}
		// Crossings: some of the inner walls between two cells go, so ways meet and loop (more than one way through).
		// mazeCrossings % of half the cells; first where a wall closes off a dead end (the dead end goes on, the other
		// side gets a T), then between two corridors but away from other junctions, so they don't pile up into open
		// spaces. Never a fourth way out of a cell, a long straight, or a wall standing on its own.
		int target = n * n * a.mazeCrossings / 200;
		if (target > 0) {
			List<int[]> walls = new ArrayList<>();
			for (int x = 1; x < w - 1; x++) {
				for (int z = 1; z < w - 1; z++) {
					// A wall between two cells: one coordinate odd (in a cell row), the other even (between two cells).
					if (!open[x][z] && (x % 2 == 1) != (z % 2 == 1)) walls.add(new int[] { x, z });
				}
			}
			java.util.Collections.shuffle(walls, random);
			int made = 0;
			for (int phase = 0; phase < 2 && made < target; phase++) {
				for (int[] c : walls) {
					if (made >= target) break;
					int x = c[0], z = c[1];
					if (open[x][z]) continue;
					int dx = x % 2 == 0 ? 1 : 0, dz = 1 - dx;
					int left = mazeWays(open, x - dx, z - dz), right = mazeWays(open, x + dx, z + dz);
					if (left >= 3 || right >= 3) continue;
					if (phase == 0 ? Math.min(left, right) != 1 : mazeNearJunction(open, x, z, dx, dz)) continue;
					// Not when it would join two corridors into one long straight one.
					int run = 1;
					for (int k = 1; x + dx * k < w && z + dz * k < w && open[x + dx * k][z + dz * k]; k++) run++;
					for (int k = 1; x - dx * k >= 0 && z - dz * k >= 0 && open[x - dx * k][z - dz * k]; k++) run++;
					if (run > 2 * (MAZE_STRAIGHT + 1) + 1) continue;
					// Not when a post at its ends would stand alone (rows of lone posts let you see far across the maze).
					if (mazeWalls(open, w, x - dz, z - dx) <= 1 || mazeWalls(open, w, x + dz, z + dx) <= 1) continue;
					open[x][z] = true;
					// Nor when what's left of the wall on either side would stand free as a short piece.
					if (!mazeWallHolds(open, w, x - dz, z - dx) || !mazeWallHolds(open, w, x + dz, z + dx)) {
						open[x][z] = false;
						continue;
					}
					made++;
				}
			}
		}
		// Dead ends (one way out) get a chest now and then; not the start or the goal.
		List<int[]> chests = new ArrayList<>();
		for (int i = 0; i < n && a.mazeChests > 0; i++) {
			for (int j = 0; j < n; j++) {
				if (i == 0 && j == 0 || i == n - 1 && j == n - 1) continue;
				int x = i * 2 + 1, z = j * 2 + 1, ways = 0;
				for (int[] d : dirs) if (open[x + d[0]][z + d[1]]) ways++;
				if (ways == 1 && random.nextInt(100) < a.mazeChests) chests.add(new int[] { i, j });
			}
		}
		// Placed over the next ticks (a big maze is millions of blocks): see stepMazes.
		// What earlier runs left lying around goes with the old maze, and whatever wandered in.
		Cleanup.items(level, mazeArea(a));
		clearMazeEntities(level, a, true);
		MazeBuild b = new MazeBuild(level, o, a.mazePath, open, blocks(a.wall), blocks(a.floor), a.wallHeight, a.mazeLight);
		b.ceiling = a.mazeCeiling.isBlank() ? List.of() : blocks(a.mazeCeiling);
		b.goal = eu.explorerseden.nicecontrolcenter.core.BlockLists.of(a.mazeGoal, Blocks.SEA_LANTERN).stream().map(Block::defaultBlockState).toList();
		b.chests = chests;
		b.loot = a.mazeLoot;
		b.area = mazeArea(a);
		b.arena = a;
		// Built bigger (or elsewhere) before: what the new one doesn't cover goes.
		MazeSpot was = mazesBuilt.get(a.id);
		ServerLevel wasLevel = was == null ? null : levelOf(level.getServer(), was.dim);
		if (was != null && wasLevel != null && (wasLevel != level || was.x != o.getX() || was.y != o.getY() || was.z != o.getZ() || was.w > b.width)) {
			b.oldLevel = wasLevel;
			b.oldOrigin = new BlockPos(was.x, was.y, was.z);
			b.oldWidth = was.w;
		}
		MazeSpot now = new MazeSpot();
		now.dim = a.corner.dim;
		now.x = o.getX();
		now.y = o.getY();
		now.z = o.getZ();
		now.w = b.width;
		mazesBuilt.put(a.id, now);
		if (mazesFile != null) write(mazesFile, Json.GSON.toJson(mazesBuilt));
		mazeBuilds.put(a.id, b);
		mazeDay.put(a.id, System.currentTimeMillis() / 86_400_000L);
	}

	/** Where each maze was last built and how big (arena id → footprint), so a smaller or moved one clears the rest. */
	private static final class MazeSpot {
		String dim;
		int x;
		int y;
		int z;
		int w;
	}

	private static Path mazesFile;
	private static Map<String, MazeSpot> mazesBuilt = new HashMap<>();

	private static Map<String, MazeSpot> readMazes(Path f) {
		try {
			if (Files.exists(f)) {
				Map<String, MazeSpot> m = Json.GSON.fromJson(Files.readString(f), new com.google.gson.reflect.TypeToken<Map<String, MazeSpot>>() {
				}.getType());
				if (m != null) return new HashMap<>(m);
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return new HashMap<>();
	}

	/** A maze being placed, column by column. */
	private static final class MazeBuild {
		final ServerLevel level;
		final BlockPos origin;
		/** Blocks along each side; the path width; the layout (cells at odd spots, walls at even ones). */
		final int width;
		final int path;
		final boolean[][] open;
		List<BlockState> ceiling = List.of();
		List<BlockState> goal = List.of();
		List<int[]> chests = List.of();
		String loot = "";
		/** The maze and a little around it, cleared of what lies around. */
		AABB area;
		/** Its game (what's in it goes again at the end). */
		Arena arena;
		/** The last build's footprint where it isn't covered by this one: cleared first (null = nothing to clear). */
		ServerLevel oldLevel;
		BlockPos oldOrigin;
		int oldWidth;
		int oldNext;
		final List<BlockState> walls;
		final List<BlockState> floors;
		final int height;
		/** Light level of the paths (0 = none): an invisible light block at head height in every cell. */
		final int light;
		int next;

		MazeBuild(ServerLevel level, BlockPos origin, int path, boolean[][] open, List<BlockState> walls, List<BlockState> floors, int height, int light) {
			this.level = level;
			this.origin = origin;
			this.path = path;
			this.width = (open.length / 2) * (path + 1) + 1;
			this.open = open;
			this.walls = walls;
			this.floors = floors;
			this.height = height;
			this.light = light;
		}
	}

	/** Arena id → its maze being placed. */
	private static final Map<String, MazeBuild> mazeBuilds = new LinkedHashMap<>();
	/** Columns placed per tick (each up to 9 blocks), shared by the mazes being built. */
	private static final int MAZE_COLUMNS_PER_TICK = 2048;

	/** True while the maze is still being placed (no runs then). */
	static boolean mazeBuilding(Arena a) {
		return mazeBuilds.containsKey(a.id);
	}

	/** Every tick: the next columns of the mazes being built; the goal glows once it's done. */
	private static void stepMazes() {
		if (mazeBuilds.isEmpty()) return;
		int budget = MAZE_COLUMNS_PER_TICK;
		for (var it = mazeBuilds.entrySet().iterator(); it.hasNext() && budget > 0;) {
			MazeBuild b = it.next().getValue();
			// First what the last (bigger or moved) maze left outside this one.
			while (b.oldLevel != null && b.oldNext < b.oldWidth * b.oldWidth && budget-- > 0) {
				int x = b.oldNext / b.oldWidth, z = b.oldNext % b.oldWidth;
				b.oldNext++;
				BlockPos col = b.oldOrigin.offset(x, 0, z);
				int rx = col.getX() - b.origin.getX(), rz = col.getZ() - b.origin.getZ();
				if (b.oldLevel == b.level && col.getY() == b.origin.getY() && rx >= 0 && rz >= 0 && rx < b.width && rz < b.width) continue;
				for (int y = 0; y <= 9; y++) {
					BlockPos at = col.above(y);
					var be = b.oldLevel.getBlockEntity(at);
					if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r) r.setLootTable(null);
					if (be instanceof net.minecraft.world.Container c) c.clearContent();
					if (!b.oldLevel.getBlockState(at).isAir()) b.oldLevel.setBlock(at, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
				}
			}
			if (b.oldLevel != null && b.oldNext < b.oldWidth * b.oldWidth) continue;
			int total = b.width * b.width;
			int span = b.path + 1;
			while (b.next < total && budget-- > 0) {
				int x = b.next / b.width, z = b.next % b.width;
				b.next++;
				// The layout spot of this block: walls on every span-th block, path in between.
				int lx = x / span * 2 + (x % span == 0 ? 0 : 1), lz = z / span * 2 + (z % span == 0 ? 0 : 1);
				boolean open = b.open[lx][lz];
				b.level.setBlock(b.origin.offset(x, 0, z), b.floors.get(random.nextInt(b.floors.size())), 2);
				// Up to 9 high: a lower wall (or ceiling) clears what a taller one left; chests are emptied first so nothing spills.
				for (int y = 1; y <= 9; y++) {
					BlockPos at = b.origin.offset(x, y, z);
					// An unopened loot chest would roll its loot and spill it when replaced: no loot table, nothing in it.
					var be = b.level.getBlockEntity(at);
					if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r) r.setLootTable(null);
					if (be instanceof net.minecraft.world.Container c) c.clearContent();
					BlockState st = y == b.height + 1 && !b.ceiling.isEmpty() ? b.ceiling.get(random.nextInt(b.ceiling.size()))
							: open || y > b.height ? Blocks.AIR.defaultBlockState() : b.walls.get(random.nextInt(b.walls.size()));
					b.level.setBlock(at, st, 2);
				}
				// Lit paths: a light in each cell's first block, at head height but under a low ceiling.
				if (b.light > 0 && open && lx % 2 == 1 && lz % 2 == 1 && x % span == 1 && z % span == 1) {
					b.level.setBlock(b.origin.offset(x, Math.min(2, b.height), z), Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, b.light), 2);
				}
			}
			if (b.next >= total) {
				int g = b.width - 1 - b.path;
				for (int x = 0; x < b.path; x++) {
					for (int z = 0; z < b.path; z++) b.level.setBlock(b.origin.offset(g + x, 0, g + z), b.goal.get(random.nextInt(b.goal.size())), 2);
				}
				placeChests(b);
				// Once more at the end: anything that came loose or wandered in while it was being placed.
				Cleanup.items(b.level, b.area);
				if (b.arena != null) clearMazeEntities(b.level, b.arena, false);
				it.remove();
			}
		}
	}

	/** The maze's dead-end chests, facing out of their dead end, filled from its loot table when opened. */
	private static void placeChests(MazeBuild b) {
		Identifier table = Identifier.tryParse(b.loot);
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int[] c : b.chests) {
			int lx = c[0] * 2 + 1, lz = c[1] * 2 + 1;
			net.minecraft.core.Direction face = net.minecraft.core.Direction.NORTH;
			for (int[] d : dirs) {
				if (b.open[lx + d[0]][lz + d[1]]) face = d[0] == 1 ? net.minecraft.core.Direction.EAST : d[0] == -1 ? net.minecraft.core.Direction.WEST : d[1] == 1 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
			}
			// Back against the dead end's far wall, in the middle of the path.
			int bx = c[0] * (b.path + 1) + 1 + b.path / 2, bz = c[1] * (b.path + 1) + 1 + b.path / 2;
			if (b.path > 1) {
				bx -= face.getStepX() * (b.path / 2);
				bz -= face.getStepZ() * (b.path / 2);
			}
			BlockPos at = b.origin.offset(bx, 1, bz);
			b.level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, face), 2);
			if (table != null && b.level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity chest) {
				chest.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table));
				chest.setLootTableSeed(random.nextLong());
			}
		}
	}

	/** "minecraft:stone_bricks, #minecraft:wool": mixed at random (tags spread out; listed twice = twice as often). */
	private static List<BlockState> blocks(String list) {
		return eu.explorerseden.nicecontrolcenter.core.BlockLists.of(list, Blocks.STONE).stream().map(Block::defaultBlockState).toList();
	}

	private static void dailyMazes(MinecraftServer server) {
		long today = System.currentTimeMillis() / 86_400_000L;
		for (Arena a : settings.arenas) {
			if (!a.type.equals("maze") || a.corner == null || !a.regenerate.equals("daily") || mazeDay.getOrDefault(a.id, today) >= today) continue;
			if (sessions.values().stream().anyMatch(s -> s.arena.id.equals(a.id))) continue;
			ServerLevel level = level(server, a.corner.dim);
			if (level != null) buildMaze(level, a);
		}
	}

	// ---------------------------------------------------------------- commands

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var arenaArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("arena", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.arenas.stream().map(a -> a.name), b));
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		d.register(Commands.literal("game").requires(Everyone.unless("nicecontrolcenter.games.use"))
				.then(Commands.literal("join").then(arenaArg.get().executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Arena a = arena(ctx);
					if (a == null || !a.enabled) return fail(ctx, "There's no such game.");
					ServerLevel level = level(ctx.getSource().getServer(), a.start.dim);
					if (level == null) return fail(ctx, "That arena's dimension isn't there.");
					if (a.type.equals("heads")) {
						HeadHunt.progress(p, a);
						return 1;
					}
					if (Games.quick(a.type)) {
						if (sessions.containsKey(p.getUUID()) || WaveRuntime.inGame(p) || QuickRuntime.inGame(p)) return fail(ctx, "You're already in a game: /game leave first.");
						p.teleportTo(level, a.start.x, freeY(level, p, a.start.x, a.start.y, a.start.z), a.start.z, Set.of(), a.start.yaw, 0, true);
						ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": you're on the pad; the round starts once the join window closes."), false);
						return 1;
					}
					if (a.type.equals("waves")) {
						if (sessions.containsKey(p.getUUID()) || WaveRuntime.inGame(p) || QuickRuntime.inGame(p)) return fail(ctx, "You're already in a game: /game leave first.");
						p.teleportTo(level, a.start.x, freeY(level, p, a.start.x, a.start.y, a.start.z), a.start.z, Set.of(), a.start.yaw, 0, true);
						ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": the party leader stands on the pad to start (or queue up)."), false);
						return 1;
					}
					String err = start(ctx.getSource().getServer(), p, a);
					return err == null ? 1 : fail(ctx, err);
				})))
				.then(Commands.literal("leave").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (WaveRuntime.leave(ctx.getSource().getServer(), p)) return 1;
					if (QuickRuntime.leave(ctx.getSource().getServer(), p)) return 1;
					Session s = sessions.get(p.getUUID());
					if (s == null) return fail(ctx, "You're not in a game.");
					end(ctx.getSource().getServer(), s, "You left the game.", false);
					return 1;
				}))
				.then(Commands.literal("top").then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a == null) return fail(ctx, "There's no such game.");
					List<Games.Score> list = top(a, 10);
					StringBuilder b = new StringBuilder(a.shown() + (list.isEmpty() ? ": no runs yet." : ":"));
					int i = 1;
					for (Games.Score sc : list) b.append("\n").append(i++).append(". ").append(sc.name).append(" · ").append(Games.scoreText(a.type, sc.best, GameRuntime::clock));
					ctx.getSource().sendSuccess(() -> Words.lit(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				})))
				.then(Commands.literal("list").executes(ctx -> {
					String names = settings.arenas.stream().filter(a -> a.enabled).map(a -> a.shown() + (a.shown().equals(a.name) ? "" : " (/game join " + a.name + ")") + " · " + a.type).reduce((x, y) -> x + ", " + y).orElse("none yet");
					ctx.getSource().sendSuccess(() -> Words.lit("Games: " + names), false);
					return 1;
				}))
				.then(Commands.literal("spawn").requires(admin)
						.then(Commands.literal("set").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							reloadNow();
							settings.hub = pos(p);
							saveSettings();
							ctx.getSource().sendSuccess(() -> Words.lit("Hub spawn set here: players who log out or die in game areas or in this dimension come back here."), true);
							return 1;
						}))
						.then(Commands.literal("clear").executes(ctx -> {
							reloadNow();
							settings.hub = null;
							saveSettings();
							ctx.getSource().sendSuccess(() -> Words.lit("Hub spawn cleared: players go to their home or respawn point again."), true);
							return 1;
						})))
				.then(Commands.literal("create").requires(admin)
						.then(Commands.argument("type", StringArgumentType.word()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(Games.TYPES, b))
								.then(Commands.argument("name", StringArgumentType.word()).executes(GameRuntime::create))))
				.then(Commands.literal("delete").requires(admin).then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a == null) return fail(ctx, "There's no such game.");
					for (Session s : List.copyOf(sessions.values())) if (s.arena.id.equals(a.id)) end(ctx.getSource().getServer(), s, "This game was removed.", false);
					WaveRuntime.stop(ctx.getSource().getServer(), a);
					QuickRuntime.stop(ctx.getSource().getServer(), a);
					settings.arenas.remove(a);
					saveSettings();
					ctx.getSource().sendSuccess(() -> Words.lit("Removed " + a.shown() + " (the blocks it built stay)."), true);
					return 1;
				})))
				// A Katters boss right here, to see it fight (gone again with /game boss clear, or the next restart).
				.then(Commands.literal("boss").requires(admin)
						.then(Commands.literal("clear").executes(ctx -> {
							int n = 0;
							for (ServerLevel l : ctx.getSource().getServer().getAllLevels()) {
								for (net.minecraft.world.entity.Entity e : l.getAllEntities()) {
									if (e.entityTags().contains(BOSS_TRY)) {
										e.discard();
										n++;
									}
								}
							}
							int gone = n;
							ctx.getSource().sendSuccess(() -> Words.lit(gone + " boss pieces taken away."), true);
							return gone;
						}))
						.then(Commands.argument("boss", StringArgumentType.greedyString())
								.suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.all(), b)).executes(ctx -> {
									String typed = StringArgumentType.getString(ctx, "boss");
									String k = eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.resolve(typed);
									if (k == null) return fail(ctx, "No such boss: " + typed + " (" + String.join(", ", eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.all()) + ").");
									net.minecraft.world.phys.Vec3 at = ctx.getSource().getPosition();
									String dim = ctx.getSource().getLevel().dimension().identifier().toString();
									var f = eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.spawn(ctx.getSource().getServer(), k, dim, at.x, at.y, at.z, List.of(BOSS_TRY), null, true);
									if (f == null) return fail(ctx, "That boss can't be summoned here.");
									ctx.getSource().sendSuccess(() -> Words.lit(eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses.title(k) + " is here. /game boss clear takes it away."), true);
									return 1;
								})))
				.then(Commands.literal("try").requires(admin).then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a != null && Games.quick(a.type)) {
						String err = QuickRuntime.trial(ctx.getSource().getServer(), a, ctx.getSource().getPlayerOrException());
						if (err != null) return fail(ctx, err);
						ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": a round with only you in it; /game leave ends it."), true);
						return 1;
					}
					if (a == null || !a.type.equals("waves")) return fail(ctx, "Only wave arenas and quick rounds can be tried.");
					String err = WaveRuntime.trial(ctx.getSource().getServer(), a);
					if (err != null) return fail(ctx, err);
					ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": wave 1 comes in 3 seconds with nobody in it; it ends in 3 minutes."), true);
					return 1;
				})))
				.then(Commands.literal("edit").requires(admin).then(arenaArg.get()
						// Wave arenas: where players appear, the fight box, mob spawns, spectator area, exit pads, kit.
						.then(Commands.literal("spawn").executes(ctx -> edit(ctx, (a, p) -> {
							// The start point (where the round gathers) moves along when it was on the spawn (or the game has none apart).
							boolean together = a.start == null || a.spawn == null || a.start.dim.equals(a.spawn.dim) && a.start.distanceSq(a.spawn) < 4;
							a.spawn = pos(p);
							if (together && (a.type.equals("waves") || Games.quick(a.type))) a.start = pos(p);
							return "Players appear here (and come back here after dying).";
						})))
						.then(Commands.literal("corner1").executes(ctx -> corner(ctx, true))
								.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(ctx -> corner(ctx, true))))
						.then(Commands.literal("corner2").executes(ctx -> corner(ctx, false))
								.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(ctx -> corner(ctx, false))))
						.then(Commands.literal("mobspawn")
								.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
									a.mobSpawns.add(pos(p));
									return "Mob spawn " + a.mobSpawns.size() + " added here.";
								})))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.mobSpawns.clear();
									return "Mob spawns cleared.";
								}))))
						.then(Commands.literal("spectate").executes(ctx -> edit(ctx, (a, p) -> {
									a.spectate = pos(p);
									return "Spectator area here (radius " + a.spectateRadius + "): " + spectateUse(a) + ".";
								}))
								.then(Commands.argument("radius", IntegerArgumentType.integer(2, 64)).executes(ctx -> edit(ctx, (a, p) -> {
									a.spectate = pos(p);
									a.spectateRadius = IntegerArgumentType.getInteger(ctx, "radius");
									return "Spectator area here (radius " + a.spectateRadius + "): " + spectateUse(a) + ".";
								})))
								// Or a box: two corners (looked at, given, or at their feet), height included.
								.then(Commands.literal("corner1").executes(ctx -> spectateCorner(ctx, true))
										.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(ctx -> spectateCorner(ctx, true))))
								.then(Commands.literal("corner2").executes(ctx -> spectateCorner(ctx, false))
										.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(ctx -> spectateCorner(ctx, false))))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.spectate = null;
									a.spectateMin = null;
									a.spectateMax = null;
									return Games.quick(a.type) ? "No spectator area: players who step on the pad during a round are queued for the next one where they are, players who are out go back to the pad."
										: "No spectator area: the dead watch a teammate, queued parties wait where they are.";
								}))))
						.then(Commands.literal("exitpad")
								.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
									a.exitPads.add(pos(p));
									return "Exit pad " + a.exitPads.size() + " here: five seconds on it leaves the game.";
								})))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.exitPads.clear();
									return "Exit pads cleared.";
								}))))
						.then(Commands.literal("kit").executes(ctx -> edit(ctx, (a, p) -> {
							a.kit = GameClaims.kitFrom(p);
							a.moreKits = new ArrayList<>();
							return "The kit is what you carry now (" + a.kit.size() + " stacks)" + ("own".equals(a.gear) ? "; it's handed out only with gear: kit (panel)." : ".")
									+ " More kits to pick from at random: kit add.";
						}))
								// One more kit: each player gets one of them at random.
								.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
									List<String> taken = GameClaims.kitFrom(p);
									if (taken.isEmpty()) return "Your inventory is empty: carry what the kit should hold first.";
									if (a.kit == null || a.kit.isEmpty()) a.kit = taken;
									else a.moreKits.add(taken);
									int n = GameClaims.kitCount(a.kit, a.moreKits);
									return n + (n == 1 ? " kit" : " kits") + " now; each player gets one of them at random"
											+ ("own".equals(a.gear) ? " (only with gear: kit, in the panel)." : ".");
								})))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.kit = new ArrayList<>();
									a.moreKits = new ArrayList<>();
									return "No kit now.";
								}))))
						.then(Commands.literal("start").executes(ctx -> edit(ctx, (a, p) -> {
							a.start = pos(p);
							return "Start pad moved here.";
						})))
						.then(Commands.literal("finish").then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 30)).executes(ctx -> edit(ctx, (a, p) -> {
							a.finish = new Games.Point(p.getX(), p.getY(), p.getZ(), DoubleArgumentType.getDouble(ctx, "radius"));
							return "Finish set here (radius " + a.finish.radius + ").";
						}))))
						.then(Commands.literal("checkpoint")
								.then(Commands.literal("add").then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 30)).executes(ctx -> edit(ctx, (a, p) -> {
									a.checkpoints.add(new Games.Point(p.getX(), p.getY(), p.getZ(), DoubleArgumentType.getDouble(ctx, "radius")));
									return "Checkpoint " + a.checkpoints.size() + " added here.";
								}))))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.checkpoints.clear();
									return "Checkpoints cleared.";
								}))))
						.then(Commands.literal("size").then(Commands.argument("cells", IntegerArgumentType.integer(4, 256)).executes(ctx -> edit(ctx, (a, p) -> {
							a.size = IntegerArgumentType.getInteger(ctx, "cells");
							return "Size " + a.size + ": /game edit " + a.name + " build makes it.";
						}))))
						.then(Commands.literal("build").executes(ctx -> edit(ctx, (a, p) -> {
							if (a.type.equals("colorfloor") && a.corner != null) {
								QuickRuntime.previewGrid((ServerLevel) p.level(), a);
								return "Grid built (each round lays it out again, colours shuffled).";
							}
							if (!a.type.equals("maze") || a.corner == null) return "Only mazes and colour floors are built.";
							buildMaze((ServerLevel) p.level(), a);
							int w = a.mazeBlocks();
								return "Building the maze (" + w + " × " + w + " blocks)" + (w * w > 2048 * 20 ? ": it takes about " + Math.max(1, w * w / 2048 / 20) + " s." : ".");
						})))
						// Colour floor: the grid's corner at their feet (platforms go +X and +Z, the floor at this height).
						.then(Commands.literal("grid").executes(ctx -> edit(ctx, (a, p) -> {
							if (a.type.equals("moles")) {
								Games.Pos at = pos(p);
								a.corner = new Games.Pos(at.dim, Math.floor(at.x), Math.floor(at.y) - 1, Math.floor(at.z), 0);
								settings.fill();
								Moles.build((ServerLevel) p.level(), a);
								// A start point (or spawn) now inside a hole block goes on top of it.
								a.start = Moles.onTop((ServerLevel) p.level(), a.start);
								a.spawn = Moles.onTop((ServerLevel) p.level(), a.spawn);
								int span = (a.moleSize - 1) * (1 + a.moleGap) + 1;
								return a.moleSize + " × " + a.moleSize + " holes laid out here (" + span + " × " + span + " blocks toward +X and +Z, in the block under your feet); heads pop up on top of them."
										+ " After changing the size or gap in the panel: grid again.";
							}
							if (!a.type.equals("colorfloor")) return "Only colour floors and whack-a-moles have a grid.";
							Games.Pos at = pos(p);
							a.corner = new Games.Pos(at.dim, Math.floor(at.x), at.y - 1, Math.floor(at.z), 0);
							// Players gather on the middle platform.
							settings.fill();
							QuickRuntime.previewGrid((ServerLevel) p.level(), a);
							int span = a.platformSize * 3 + a.platformGap * 2;
							return "Grid built here: " + span + " × " + span + " blocks (+X and +Z, at the block under your feet). Keep the space below open: falling = out.";
						})))
						// Snowball gallery: lanes the mobs walk along.
						.then(Commands.literal("lane")
								.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
									if (!a.type.equals("gallery")) return "Only snowball galleries have lanes.";
									Games.Lane l = new Games.Lane();
									l.from = pos(p);
									a.galleryLanes.add(l);
									return "Lane " + a.galleryLanes.size() + " starts here. Walk to where it ends and type /game edit " + a.name + " lane end.";
								})))
								.then(Commands.literal("end").executes(ctx -> edit(ctx, (a, p) -> {
									Games.Lane open = a.galleryLanes.stream().filter(l -> l.to == null).reduce((x, y) -> y).orElse(null);
									if (open == null) return "No lane waiting for its end: /game edit " + a.name + " lane add at its start first.";
									Games.Pos here = pos(p);
									if (!here.dim.equals(open.from.dim)) return "A lane ends in the dimension it starts in.";
									open.to = here;
									return "Lane " + (a.galleryLanes.indexOf(open) + 1) + " ends here (" + Math.round(Math.sqrt(open.from.distanceSq(here))) + " blocks long). Mobs walk it from start to end.";
								})))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.galleryLanes.clear();
									return "All lanes removed.";
								}))))
						// Cauldron chef: supply chests, filled with what they hold now at each round.
						.then(Commands.literal("station")
								.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
									if (!a.type.equals("chef")) return "Only cauldron chefs have supply chests.";
									var hit = p.pick(6, 1f, false);
									BlockPos at = hit instanceof net.minecraft.world.phys.BlockHitResult b && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? b.getBlockPos() : null;
									if (at == null || !(p.level().getBlockEntity(at) instanceof net.minecraft.world.Container c)) return "Look at a chest or barrel (6 blocks at most); nothing changed.";
									Games.Pos where = new Games.Pos(dim(p), at.getX(), at.getY(), at.getZ(), 0);
									a.chefStations.removeIf(st -> st.pos.dim.equals(where.dim) && st.pos.distanceSq(where) < 0.5);
									Games.Station st = new Games.Station();
									st.pos = where;
									st.items = Chef.snapshot((ServerLevel) p.level(), c);
									a.chefStations.add(st);
									return "Supply chest " + a.chefStations.size() + " at " + at.toShortString() + ": filled with these " + st.items.size() + " stacks at each round (and every 30 seconds).";
								})))
								.then(Commands.literal("remove").executes(ctx -> edit(ctx, (a, p) -> {
									var hit = p.pick(6, 1f, false);
									if (!(hit instanceof net.minecraft.world.phys.BlockHitResult b) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return "Look at the supply chest; nothing changed.";
									BlockPos at = b.getBlockPos();
									boolean gone = a.chefStations.removeIf(st -> st.pos.dim.equals(dim(p)) && BlockPos.containing(st.pos.x, st.pos.y, st.pos.z).equals(at));
									return gone ? "Not a supply chest any more (" + a.chefStations.size() + " left)." : "That isn't a supply chest.";
								})))
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.chefStations.clear();
									return "All supply chests removed (the chests stay).";
								}))))
						// Ways in and out, placed anywhere (e.g. in the hub): particles and a label above each.
						.then(points("entrance", a -> a.entrances, "Entrance", "stepping in for a second takes players to the game; its name and top 3 float above it"))
						.then(points("watch", a -> a.watchEntrances, "Watch point", "stepping in takes players to the spectator area (/game edit … spectate)"))
						.then(points("back", a -> a.exitPads, "Back point", "the way out of spectator areas and the like: to the game hub spawn (inside a wave game or quick round: 5 seconds on it leaves the game)"))
						// What players see, spaces allowed (the one-word name stays for the commands).
						.then(Commands.literal("title").then(Commands.argument("title", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, (a, p) -> {
							String t = StringArgumentType.getString(ctx, "title").strip();
							if (t.length() > 48) return "Up to 48 characters, please (nothing changed).";
							a.title = t.equals(a.name) ? "" : t;
							return "Players see it as \"" + a.shown() + "\" now; commands still use " + a.name + ".";
						}))))
						// Head hunts.
						.then(Commands.literal("texture").then(Commands.argument("texture", StringArgumentType.greedyString()).executes(ctx -> edit(ctx, (a, p) -> {
							String t = StringArgumentType.getString(ctx, "texture").strip();
							if (eu.explorerseden.nicecontrolcenter.Pets.texture(t) == null) return "That isn't a head texture: use a textures.minecraft.net URL, its hash, or the base64 value.";
							a.headTexture = t;
							return "Head skin set; heads already hidden take it within a few seconds. /game edit " + a.name + " headitem gives you heads to hide.";
						}))))
						.then(Commands.literal("headitem").executes(ctx -> headItem(ctx, 16))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> headItem(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
						.then(Commands.literal("head")
								.then(Commands.literal("add").executes(ctx -> lookedAtHead(ctx, true)))
								.then(Commands.literal("remove").executes(ctx -> lookedAtHead(ctx, false)))))));
	}

	/** {player}, {game}, {score} in an arena's own lines and commands. */
	private static String fill(String text, Arena a, ServerPlayer p, String score) {
		return Games.math(text, Map.of("score", score)).replace("{player}", p.getGameProfile().name()).replace("{game}", a.shown()).replace("{score}", score);
	}

	private static void commands(MinecraftServer server, List<String> lines, Arena a, ServerPlayer p, String score) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), a, p, score);
			if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
		}
	}

	private interface Edit {
		String apply(Arena a, ServerPlayer p);
	}

	private static int edit(CommandContext<CommandSourceStack> ctx, Edit e) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Arena a = arena(ctx);
		if (a == null) return fail(ctx, "There's no such game.");
		String msg = e.apply(a, p);
		saveSettings();
		ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": " + msg), true);
		return 1;
	}

	/** A wave arena's fight box corner: given, looked at, or at their feet; the box is as high as its corners. */
	private static int corner(CommandContext<CommandSourceStack> ctx, boolean first) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Arena a = arena(ctx);
		if (a == null) return fail(ctx, "There's no such game.");
		BlockPos at = Corners.pick(ctx, p);
		Games.Pos pos = new Games.Pos(dim(p), at.getX(), at.getY(), at.getZ(), 0);
		if (first) a.areaMin = pos;
		else a.areaMax = pos;
		saveSettings();
		Games.Pos other = first ? a.areaMax : a.areaMin;
		BlockPos o = other == null || !other.dim.equals(pos.dim) ? null : BlockPos.containing(other.x, other.y, other.z);
		Corners.show(p, at, o);
		String msg = "Corner " + (first ? 1 : 2) + " at " + at.toShortString() + (o == null ? ". Now the opposite corner (height included): /game edit " + a.name + (first ? " corner2" : " corner1") + " [x y z] or look at it."
				: ". Box " + Corners.size(at, o) + ".");
		ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": " + msg), true);
		return 1;
	}

	/** A spectator area corner; without a spectate point yet, it gets one in the middle of the box (on its floor). */
	private static int spectateCorner(CommandContext<CommandSourceStack> ctx, boolean first) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Arena a = arena(ctx);
		if (a == null) return fail(ctx, "There's no such game.");
		BlockPos at = Corners.pick(ctx, p);
		Games.Pos pos = new Games.Pos(dim(p), at.getX(), at.getY(), at.getZ(), 0);
		if (first) a.spectateMin = pos;
		else a.spectateMax = pos;
		Games.Pos other = first ? a.spectateMax : a.spectateMin;
		BlockPos o = other == null || !other.dim.equals(pos.dim) ? null : BlockPos.containing(other.x, other.y, other.z);
		if (o != null && (a.spectate == null || !Games.inSpectate(a, a.spectate.dim, a.spectate.x, a.spectate.y, a.spectate.z, 0))) {
			a.spectate = new Games.Pos(pos.dim, (at.getX() + o.getX()) / 2.0 + 0.5, Math.min(at.getY(), o.getY()) + 1, (at.getZ() + o.getZ()) / 2.0 + 0.5, 0);
		}
		saveSettings();
		Corners.show(p, at, o);
		String msg = "Spectator area corner " + (first ? 1 : 2) + " at " + at.toShortString() + (o == null ? ". Now the opposite corner (height included): /game edit " + a.name + " spectate " + (first ? "corner2" : "corner1") + " [x y z] or look at it."
				: ". Spectator area " + Corners.size(at, o) + "; players are put at " + Math.round(a.spectate.x) + " " + Math.round(a.spectate.y) + " " + Math.round(a.spectate.z) + " (/game edit " + a.name + " spectate moves that spot).");
		ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": " + msg), true);
		return 1;
	}

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		reloadNow();
		String type = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
		String name = StringArgumentType.getString(ctx, "name");
		if (!Games.TYPES.contains(type)) return fail(ctx, "Types: " + String.join(", ", Games.TYPES) + ".");
		if (settings.arena(name) != null) return fail(ctx, "There's a game called " + name + " already.");
		Arena a = new Arena();
		a.name = name;
		a.type = type;
		a.start = pos(p);
		String next = switch (type) {
			case "parkour" -> "This spot is the start point; lanes go off along +X every " + a.laneSpacing + " blocks, so keep that space free. Or keep it in a box: /game edit " + name + " corner1 / corner2 (lanes share it, 9 blocks wide each).";
			case "race" -> "This spot is the start. Add checkpoints in order with /game edit " + name + " checkpoint add <radius>, then /game edit " + name + " finish <radius>.";
			case "waves" -> "Players appear here (the spawn; the party leader stands here to start). Then: corner1 / corner2 [x y z] (the fight box, height included),"
					+ " mobspawn add (where mobs come from; several), optionally spectate [radius] and back add, and kit. Waves and rewards are set in the panel (Games).";
			case "ghasts" -> "Players appear here (the spawn). Then: /game edit " + name + " corner1 / corner2 (the play box, height included),"
					+ " mobspawn add (where ghasts appear, up high; several). Try it with /game try " + name + ".";
			case "colorfloor" -> "Stand where the grid's corner goes and type /game edit " + name + " grid (it builds 3×3 platforms toward +X and +Z; players appear on the middle one). Colours, speed and mobs are set in the panel.";
			case "anvils", "treasure", "fishing" -> "Players appear here (the spawn). Then: /game edit " + name + " corner1 / corner2 (the play box, height included"
					+ (type.equals("anvils") ? "; anvils fall from its top" : type.equals("fishing") ? "; put water in it" : "; treasure appears on its floor") + "). Try it with /game try " + name + ".";
			case "moles" -> "Players appear here (the spawn). Stand where the grid's corner goes and type /game edit " + name + " grid (holes toward +X and +Z)."
					+ " Heads and their points are set in the panel. Try it with /game try " + name + ".";
			case "targets" -> "Players shoot from here (the spawn). Then: /game edit " + name + " corner1 / corner2 (the box targets appear in, height included). Try it with /game try " + name + ".";
			case "gallery" -> "Players throw from here (the spawn). Then: /game edit " + name + " corner1 / corner2 (where players stay), and lanes: /game edit " + name
					+ " lane add at a lane's start, lane end at its end (several). Mobs and their points are set in the panel.";
			case "chef" -> "Players appear here (the spawn). Then: /game edit " + name + " corner1 / corner2 (the kitchen, height included), fill chests with ingredients and"
					+ " /game edit " + name + " station add looking at each, and put an NPC there as the cook (its name goes in the panel). Orders are set in the panel.";
			case "lava" -> "Players appear here (the spawn, at the bottom). Then: /game edit " + name + " corner1 / corner2 (the tower or cave the lava fills, height included;"
					+ " build it of blocks that don't burn). Try it with /game try " + name + ".";
			case "heads" -> "Set the heads' skin: /game edit " + name + " texture <url or value>, then /game edit " + name + " headitem gives you heads to hide (placing one adds it)."
					+ " Rewards every few heads are set in the panel.";
			default -> "The maze is built from this corner (" + a.mazeBlocks() + " blocks each way, +X and +Z). The start point is here too; move it with /game edit " + name + " start.";
		};
		// Sensible starts for the quick rounds.
		switch (type) {
			case "ghasts" -> {
				a.pool = new ArrayList<>(List.of("ghast {ExplosionPower:0}"));
				a.maxAlive = 4;
				a.timeLimit = 90;
			}
			case "colorfloor" -> {
				a.pool = new ArrayList<>(List.of("zombie"));
				a.maxAlive = 4;
			}
			case "treasure" -> {
				a.maxAlive = 8;
				a.timeLimit = 60;
			}
			case "maze" -> {
				a.pool = new ArrayList<>();
				a.maxAlive = 6;
			}
			case "fishing" -> a.timeLimit = 120;
			case "anvils" -> a.timeLimit = 300;
			case "moles", "targets" -> {
				a.maxAlive = 3;
				a.timeLimit = 60;
			}
			case "gallery" -> {
				a.pool = new ArrayList<>(List.of("zombie", "skeleton", "chicken", "rabbit", "villager"));
				a.maxAlive = 6;
				a.timeLimit = 90;
			}
			case "chef" -> a.timeLimit = 180;
			case "lava" -> a.timeLimit = 0;
			default -> {
			}
		}
		// Wave fights and quick rounds: players appear where it was made; they gather there for the round too.
		if (type.equals("waves") || Games.quick(type) && !type.equals("colorfloor")) a.spawn = pos(p);
		if (type.equals("maze")) {
			a.corner = pos(p);
			a.start = new Games.Pos(a.corner.dim, a.corner.x - 2, a.corner.y, a.corner.z - 2, 0);
		}
		settings.arenas.add(a);
		settings.fill();
		saveSettings();
		if (type.equals("maze")) buildMaze((ServerLevel) p.level(), a);
		ctx.getSource().sendSuccess(() -> Words.lit("Made the " + type + " game " + name + ". " + next + (type.equals("heads") ? "" : " Then its entrance, e.g. in the hub: /game edit " + name + " entrance add (and a display name: /game edit " + name + " title …).")).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	/** add (here) | remove (the nearest within 5 blocks) | clear, for a list of points. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> points(String word, java.util.function.Function<Arena, List<Games.Pos>> list, String label, String what) {
		return Commands.literal(word)
				.then(Commands.literal("add").executes(ctx -> edit(ctx, (a, p) -> {
					list.apply(a).add(pos(p));
					return label + " " + list.apply(a).size() + " here: " + what + ".";
				})))
				.then(Commands.literal("remove").executes(ctx -> edit(ctx, (a, p) -> {
					Games.Pos near = null;
					double best = 25;
					for (Games.Pos x : list.apply(a)) {
						if (!x.dim.equals(dim(p))) continue;
						double d = p.distanceToSqr(x.x, x.y, x.z);
						if (d < best) {
							best = d;
							near = x;
						}
					}
					if (near == null) return "No " + label.toLowerCase(Locale.ROOT) + " within 5 blocks (nothing changed).";
					list.apply(a).remove(near);
					return label + " removed (" + list.apply(a).size() + " left).";
				})))
				.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
					list.apply(a).clear();
					return "All " + label.toLowerCase(Locale.ROOT) + "s removed.";
				})));
	}

	private static int headItem(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Arena a = arena(ctx);
		if (a == null || !a.type.equals("heads")) return fail(ctx, "There's no such head hunt.");
		net.minecraft.world.item.ItemStack st = HeadHunt.headItem(p, a, count);
		if (st == null) return fail(ctx, "Set the heads' skin first: /game edit " + a.name + " texture <url or value>.");
		if (!p.getInventory().add(st)) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
		ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": here are heads to hide; each one you place joins the hunt (break it in creative to take it out)."), false);
		return 1;
	}

	/** Adds or takes out the player head they look at. */
	private static int lookedAtHead(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Arena a = arena(ctx);
		if (a == null || !a.type.equals("heads")) return fail(ctx, "There's no such head hunt.");
		var hit = p.pick(8, 1f, false);
		if (!(hit instanceof net.minecraft.world.phys.BlockHitResult b) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !HeadHunt.isHead(p.level().getBlockState(b.getBlockPos()))) {
			return fail(ctx, "Look at a player head (8 blocks at most).");
		}
		boolean done = add ? HeadHunt.add(a, p.level(), b.getBlockPos()) : HeadHunt.remove(a, p.level(), b.getBlockPos());
		String msg = add ? (done ? "Head added (" + a.heads.size() + " hidden); it takes the hunt's skin." : "That head is in the hunt already.")
				: (done ? "Head taken out (" + a.heads.size() + " left)." : "That head isn't in the hunt.");
		ctx.getSource().sendSuccess(() -> Words.lit(a.shown() + ": " + msg), true);
		return done ? 1 : 0;
	}

	/** What the spectator area is for, by type. */
	private static String spectateUse(Arena a) {
		return Games.quick(a.type) ? "players who step on the pad during a round queue here for the next one, players who are out watch from here"
				: a.type.equals("waves") ? "the dead wait here, other parties queue here" : "only wave arenas and quick rounds use it";
	}

	private static Arena arena(CommandContext<CommandSourceStack> ctx) {
		// What the panel saved a moment ago counts: read now (not only every 5 s), so this command neither works with
		// old settings (a maze built at its old size) nor writes them back over the panel's.
		reloadNow();
		return settings.arena(StringArgumentType.getString(ctx, "arena"));
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Words.lit(why));
		return 0;
	}

	// ---------------------------------------------------------------- scores and helpers

	/** A run that counts: one more run, and the score kept if it's their best; true for a new personal best. */
	static boolean recordScore(Arena a, ServerPlayer p, long score) {
		Map<String, Games.Score> board = scores.computeIfAbsent(a.id, k -> new LinkedHashMap<>());
		Games.Score sc = board.computeIfAbsent(p.getStringUUID(), k -> new Games.Score());
		sc.name = p.getGameProfile().name();
		sc.runs++;
		boolean better = sc.at == 0 || (Games.higherIsBetter(a.type) ? score > sc.best : score < sc.best);
		if (better) {
			sc.best = score;
			sc.at = System.currentTimeMillis();
		}
		saveScores();
		return better;
	}

	/** A count that only grows (heads found): their best is the count now, without counting a run. */
	static void setBest(Arena a, ServerPlayer p, long score) {
		Map<String, Games.Score> board = scores.computeIfAbsent(a.id, k -> new LinkedHashMap<>());
		Games.Score sc = board.computeIfAbsent(p.getStringUUID(), k -> new Games.Score());
		sc.name = p.getGameProfile().name();
		if (score <= sc.best && sc.at != 0) return;
		sc.best = score;
		sc.at = System.currentTimeMillis();
		saveScores();
	}

	/** A back point: their solo run ends (it doesn't count). */
	static void leaveRun(MinecraftServer server, ServerPlayer p) {
		Session s = sessions.get(p.getUUID());
		if (s != null) end(server, s, "You left the game.", false);
	}

	/** The game's own points when a score counts: the fixed amount, plus so many per score (scores, not times). */
	static void points(ServerPlayer p, Arena a, long score, boolean timed) {
		long n = a.pointsFixed + (timed ? 0 : Math.round(a.pointsPerScore * score));
		if (n <= 0 || !eu.explorerseden.nicecontrolcenter.players.PointsStore.settings().enabled) return;
		// Quietly: the game shows its own score (the balance and its history say where they came from).
		eu.explorerseden.nicecontrolcenter.players.PointsStore.add(p.getStringUUID(), p.getGameProfile().name(), n,
				a.shown() + ": " + Games.scoreText(a.type, score, GameRuntime::clock));
	}

	/** The game of their parkour, race or maze run (null when none). */
	public static Arena arenaOf(ServerPlayer p) {
		Session s = sessions.get(p.getUUID());
		return s == null ? null : s.arena;
	}

	/** Players running this arena now (parkour, race, maze). */
	public static int running(String arenaId) {
		return (int) sessions.values().stream().filter(s -> s.arena.id.equals(arenaId)).count();
	}

	/** In a parkour, race or maze run. */
	/** The game they're playing (a run, a wave game or a quick round); null when none. */
	public static Arena playingIn(ServerPlayer p) {
		Session s = sessions.get(p.getUUID());
		if (s != null) return s.arena;
		Arena w = WaveRuntime.arenaOf(p);
		if (w != null) return w;
		QuickRuntime.Round r = QuickRuntime.roundOf(p);
		return r == null ? null : r.arena;
	}

	public static boolean inRun(ServerPlayer p) {
		return sessions.containsKey(p.getUUID());
	}

	/** The best players of an arena. */
	public static List<Games.Score> top(Arena a, int n) {
		List<Games.Score> list = new ArrayList<>(scores.getOrDefault(a.id, Map.of()).values());
		list.sort((x, y) -> Games.higherIsBetter(a.type) ? Long.compare(y.best, x.best) : Long.compare(x.best, y.best));
		return list.size() > n ? list.subList(0, n) : list;
	}

	/** For the panel: every arena with players in it now and its top scores. */
	public static List<Map<String, Object>> view() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Arena a : settings.arenas) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", a.id);
			if (a.type.equals("waves")) m.putAll(WaveRuntime.live(a.id));
			else if (Games.quick(a.type)) m.putAll(QuickRuntime.live(a.id));
			else if (a.type.equals("heads")) m.putAll(HeadHunt.live(a));
			else m.put("playing", sessions.values().stream().filter(s -> s.arena.id.equals(a.id)).count());
			m.put("top", top(a, 10));
			out.add(m);
		}
		return out;
	}

	public static boolean resetScores(String arenaId) {
		boolean had = scores.remove(arenaId) != null;
		HeadHunt.reset(arenaId);
		saveScores();
		return had;
	}

	public static String clock(long ms) {
		return Games.clock(ms);
	}

	private static Games.Pos pos(ServerPlayer p) {
		return new Games.Pos(dim(p), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, Math.round(p.getYRot() / 90f) * 90f);
	}

	private static String dim(ServerPlayer p) {
		return p.level().dimension().identifier().toString();
	}

	private static String startDim(Arena a) {
		return a.start == null ? "" : a.start.dim;
	}

	public static ServerLevel levelOf(MinecraftServer server, String dim) {
		return level(server, dim);
	}

	private static ServerLevel level(MinecraftServer server, String dim) {
		Identifier id = Identifier.tryParse(dim);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static Block block(String id) {
		Identifier i = Identifier.tryParse(id);
		Block b = i == null ? null : BuiltInRegistries.BLOCK.getValue(i);
		return b == null || b == Blocks.AIR ? Blocks.STONE : b;
	}

	private static void saveScores() {
		if (scoresFile == null) return;
		write(scoresFile, Json.GSON.toJson(scores));
	}

	private static void savePlaced() {
		placedDirty = false;
		if (placedFile == null) return;
		write(placedFile, Json.GSON.toJson(placed));
	}

	/** Parkour blocks, quick-round moles and targets, and rising lava left by a crash: gone. */
	private static void clearLeftovers(MinecraftServer server) {
		placed.clear();
		try {
			if (placedFile == null || !Files.exists(placedFile)) return;
			String[] list = Json.GSON.fromJson(Files.readString(placedFile), String[].class);
			if (list == null) return;
			for (String key : list) {
				String[] k = key.split("\\|");
				if (k.length == 8 && k[0].equals("lava")) {
					ServerLevel lv = level(server, k[1]);
					if (lv != null) Lava.clear(lv, new BlockPos(Integer.parseInt(k[2]), Integer.parseInt(k[3]), Integer.parseInt(k[4])), new BlockPos(Integer.parseInt(k[5]), Integer.parseInt(k[6]), Integer.parseInt(k[7])));
					continue;
				}
				ServerLevel level = k.length == 4 ? level(server, k[0]) : null;
				if (level != null) level.setBlock(new BlockPos(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3])), Blocks.AIR.defaultBlockState(), 3);
			}
			Files.deleteIfExists(placedFile);
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Games: could not clear old parkour blocks: {}", e.getMessage());
		}
	}

	private static void write(Path f, String text) {
		try {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Games: could not save {}: {}", f.getFileName(), e.getMessage());
		}
	}
}
