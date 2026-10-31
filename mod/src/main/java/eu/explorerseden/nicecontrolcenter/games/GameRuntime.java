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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Runs the mini-game arenas (see {@link Games}): stepping into an entrance, onto its start point or /game join starts a run, an
 * action-bar line shows how it goes, and the end keeps a personal best and fires the Rewards triggers
 * "Game finished" and "New personal best". Templates: infinite parkour (jumps made ahead of you, each player in their
 * own lane), race course (checkpoints in order; running, boat or elytra), dropper (fall into the water at the bottom)
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
		/** Parkour: jumps; race: next checkpoint; dropper: tries. */
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

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			scoresFile = server.getWorldPath(LevelResource.ROOT).resolve(Games.SCORES);
			scores = Games.readScores(scoresFile);
			placedFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-games-blocks.json");
			clearLeftovers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Session s : List.copyOf(sessions.values())) end(server, s, null, false);
			savePlaced();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) reload();
			if (server.getTickCount() % 2 == 0) tick(server);
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
				case "dropper" -> dropper(server, p, s);
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
			case "dropper" -> clock(s.millis()) + (s.progress > 0 ? " · try " + (s.progress + 1) : "") + best(s, " · best ");
			default -> clock(s.millis()) + best(s, " · best ");
		};
		p.sendOverlayMessage(Component.literal(s.arena.shown() + " · " + line).withStyle(st -> st.withColor(s.arena.rgb())));
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
			case "dropper" -> {
				if (a.top == null || a.finish == null) return "This dropper needs a top and a finish (/game edit).";
				sessions.put(p.getUUID(), s);
				p.teleportTo(level, a.top.x, a.top.y, a.top.z, Set.of(), a.top.yaw, 0, true);
			}
			case "maze" -> {
				if (a.corner == null) return "This maze has no corner yet.";
				sessions.put(p.getUUID(), s);
				p.teleportTo(level, a.corner.x + 1.5, a.corner.y + 1, a.corner.z + 1.5, Set.of(), 0, 0, true);
			}
			case "waves" -> {
				return "Wave arenas start from their pad (with your party).";
			}
			default -> {
				return "Unknown game.";
			}
		}
		level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
		String hint = switch (a.type) {
			case "parkour" -> "Jump as far as you can!";
			case "race" -> "Pass the " + a.checkpoints.size() + " checkpoints in order, then the finish." + (a.timeLimit > 0 ? " You have " + clock(a.timeLimit * 1000L) + "." : "");
			case "dropper" -> "Land in the water at the bottom." + (a.maxTries > 0 ? " " + a.maxTries + " tries." : "");
			default -> "Find the way to the glowing corner.";
		};
		p.sendSystemMessage(Component.literal(a.startMessage.isBlank() ? "▶ " + a.shown() + " started. " + hint + " /game leave stops." : fill(a.startMessage, a, p, "")).withStyle(ChatFormatting.GREEN));
		commands(server, a.startCommands, a, p, "");
		return null;
	}

	/** Ends a run: finished = it counts (score kept, rewards). */
	static void end(MinecraftServer server, Session s, String why, boolean finished) {
		sessions.remove(s.player);
		cooldown.put(s.player, System.currentTimeMillis() + s.arena.cooldown * 1000L);
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
				p.sendSystemMessage(Component.literal(line + (record ? " · new personal best!" : "")).withStyle(record ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
				commands(server, s.arena.finishCommands, s.arena, p, result);
			}
			else if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
			// To the game hub spawn, else the start point (not after dying: the respawn does that; not when they left for another dimension).
			if (p.isAlive() && (why == null || !why.startsWith("You left the arena"))) {
				if (!Portals.returnTo(server, p, s.arena) && level != null) p.teleportTo(level, s.arena.start.x, s.arena.start.y, s.arena.start.z, Set.of(), s.arena.start.yaw, 0, true);
				cooldown.put(s.player, System.currentTimeMillis() + Math.max(3, s.arena.cooldown) * 1000L);
			}
			if (finished) {
				RewardsControl.fireGame(server, "gameFinished", p, s.arena.name, score, !Games.higherIsBetter(s.arena.type));
				if (record) RewardsControl.fireGame(server, "gameRecord", p, s.arena.name, score, !Games.higherIsBetter(s.arena.type));
			}
		}
		if (s.arena.type.equals("maze") && finished && s.arena.regenerate.equals("run") && level != null && sessions.values().stream().noneMatch(o -> o.arena == s.arena)) buildMaze(level, s.arena);
	}

	// ---------------------------------------------------------------- parkour

	private static int freeLane(Arena a) {
		int lanes = laneCount(a);
		boolean[] used = new boolean[lanes];
		for (Session s : sessions.values()) if (s.arena == a && s.lane >= 0 && s.lane < lanes) used[s.lane] = true;
		for (int i = 0; i < lanes; i++) if (!used[i]) return i;
		return -1;
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
			default -> block(a.palette.get(random.nextInt(a.palette.size()))).defaultBlockState();
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
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal("Level " + (lvl + 1)).withStyle(ChatFormatting.GOLD)));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(switch (lvl) {
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
			p.sendOverlayMessage(Component.literal("★ " + s.progress + " jumps!").withStyle(ChatFormatting.GOLD));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
			for (String line : a.everyCommands) {
				String cmd = fill(line.strip().replaceFirst("^/", ""), a, p, jumps).replace("{jumps}", jumps);
				if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
			}
		}
		RewardsControl.fireEvery(server, "parkourJumps", p, a.name, s.progress);
	}

	// ---------------------------------------------------------------- race, dropper, maze

	private static boolean inside(ServerPlayer p, Games.Point pt) {
		double dx = p.getX() - pt.x, dy = p.getY() - pt.y, dz = p.getZ() - pt.z;
		return dx * dx + dy * dy + dz * dz <= pt.radius * pt.radius;
	}

	private static void race(MinecraftServer server, ServerPlayer p, Session s) {
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
					String cmd = fill(line.strip().replaceFirst("^/", ""), s.arena, p, cp).replace("{checkpoint}", cp);
					if (!cmd.isEmpty()) eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
				}
			}
			return;
		}
		if (s.arena.finish == null || inside(p, s.arena.finish)) end(server, s, null, true);
	}

	private static void dropper(MinecraftServer server, ServerPlayer p, Session s) {
		Arena a = s.arena;
		if (p.isInWater() && inside(p, a.finish)) {
			end(server, s, null, true);
			return;
		}
		// Landed anywhere else (or fell out): back to the top.
		boolean landed = p.onGround() && p.getY() < a.top.y - 2;
		if (landed || p.getY() < a.finish.y - a.finish.radius - 20) {
			s.progress++;
			if (a.maxTries > 0 && s.progress >= a.maxTries) {
				end(server, s, "Out of tries: run over.", false);
				return;
			}
			p.teleportTo((ServerLevel) p.level(), a.top.x, a.top.y, a.top.z, Set.of(), a.top.yaw, 0, true);
			p.resetFallDistance();
			p.level().playSound(null, p.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 0.6f, 1.6f);
		}
	}

	private static void maze(MinecraftServer server, ServerPlayer p, Session s) {
		Arena a = s.arena;
		int n = a.size;
		double fx = a.corner.x + (n - 1) * 2 + 1.5, fz = a.corner.z + (n - 1) * 2 + 1.5;
		double dx = p.getX() - fx, dz = p.getZ() - fz;
		if (dx * dx + dz * dz < 1.2 && Math.abs(p.getY() - (a.corner.y + 1)) < 2) end(server, s, null, true);
	}

	/** A maze of size × size cells (each 1 block of path, walls between), 3 high, path from the corner to the far corner. */
	static void buildMaze(ServerLevel level, Arena a) {
		int n = a.size;
		int w = n * 2 + 1;
		BlockPos o = BlockPos.containing(a.corner.x, a.corner.y, a.corner.z);
		boolean[][] open = new boolean[w][w];
		boolean[][] seen = new boolean[n][n];
		Deque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[] { 0, 0 });
		seen[0][0] = true;
		open[1][1] = true;
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		while (!stack.isEmpty()) {
			int[] c = stack.peek();
			List<int[]> next = new ArrayList<>();
			for (int[] d : dirs) {
				int x = c[0] + d[0], z = c[1] + d[1];
				if (x >= 0 && z >= 0 && x < n && z < n && !seen[x][z]) next.add(new int[] { x, z });
			}
			if (next.isEmpty()) {
				stack.pop();
				continue;
			}
			int[] to = next.get(random.nextInt(next.size()));
			seen[to[0]][to[1]] = true;
			open[c[0] + to[0] + 1][c[1] + to[1] + 1] = true;
			open[to[0] * 2 + 1][to[1] * 2 + 1] = true;
			stack.push(to);
		}
		BlockState wall = block(a.wall).defaultBlockState();
		BlockState floor = block(a.floor).defaultBlockState();
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < w; z++) {
				level.setBlock(o.offset(x, 0, z), floor, 2);
				// Up to 8 high: a lower wall height clears what a taller one left.
				for (int y = 1; y <= 8; y++) level.setBlock(o.offset(x, y, z), open[x][z] || y > a.wallHeight ? Blocks.AIR.defaultBlockState() : wall, 2);
			}
		}
		// The goal glows.
		level.setBlock(o.offset(w - 2, 0, w - 2), Blocks.SEA_LANTERN.defaultBlockState(), 2);
		mazeDay.put(a.id, System.currentTimeMillis() / 86_400_000L);
	}

	private static void dailyMazes(MinecraftServer server) {
		long today = System.currentTimeMillis() / 86_400_000L;
		for (Arena a : settings.arenas) {
			if (!a.type.equals("maze") || a.corner == null || !a.regenerate.equals("daily") || mazeDay.getOrDefault(a.id, today) >= today) continue;
			if (sessions.values().stream().anyMatch(s -> s.arena == a)) continue;
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
						p.teleportTo(level, a.start.x, a.start.y, a.start.z, Set.of(), a.start.yaw, 0, true);
						ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": you're on the pad; the round starts once the join window closes."), false);
						return 1;
					}
					if (a.type.equals("waves")) {
						if (sessions.containsKey(p.getUUID()) || WaveRuntime.inGame(p) || QuickRuntime.inGame(p)) return fail(ctx, "You're already in a game: /game leave first.");
						p.teleportTo(level, a.start.x, a.start.y, a.start.z, Set.of(), a.start.yaw, 0, true);
						ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": the party leader stands on the pad to start (or queue up)."), false);
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
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				})))
				.then(Commands.literal("list").executes(ctx -> {
					String names = settings.arenas.stream().filter(a -> a.enabled).map(a -> a.shown() + (a.shown().equals(a.name) ? "" : " (/game join " + a.name + ")") + " · " + a.type).reduce((x, y) -> x + ", " + y).orElse("none yet");
					ctx.getSource().sendSuccess(() -> Component.literal("Games: " + names), false);
					return 1;
				}))
				.then(Commands.literal("spawn").requires(admin)
						.then(Commands.literal("set").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							settings.hub = pos(p);
							saveSettings();
							ctx.getSource().sendSuccess(() -> Component.literal("Hub spawn set here: players who log out or die in game areas or in this dimension come back here."), true);
							return 1;
						}))
						.then(Commands.literal("clear").executes(ctx -> {
							settings.hub = null;
							saveSettings();
							ctx.getSource().sendSuccess(() -> Component.literal("Hub spawn cleared: players go to their home or respawn point again."), true);
							return 1;
						})))
				.then(Commands.literal("create").requires(admin)
						.then(Commands.argument("type", StringArgumentType.word()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(Games.TYPES, b))
								.then(Commands.argument("name", StringArgumentType.word()).executes(GameRuntime::create))))
				.then(Commands.literal("delete").requires(admin).then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a == null) return fail(ctx, "There's no such game.");
					for (Session s : List.copyOf(sessions.values())) if (s.arena == a) end(ctx.getSource().getServer(), s, "This game was removed.", false);
					WaveRuntime.stop(ctx.getSource().getServer(), a);
					QuickRuntime.stop(ctx.getSource().getServer(), a);
					settings.arenas.remove(a);
					saveSettings();
					ctx.getSource().sendSuccess(() -> Component.literal("Removed " + a.shown() + " (the blocks it built stay)."), true);
					return 1;
				})))
				.then(Commands.literal("try").requires(admin).then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a != null && Games.quick(a.type)) {
						String err = QuickRuntime.trial(ctx.getSource().getServer(), a, ctx.getSource().getPlayerOrException());
						if (err != null) return fail(ctx, err);
						ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": a round with only you in it; /game leave ends it."), true);
						return 1;
					}
					if (a == null || !a.type.equals("waves")) return fail(ctx, "Only wave arenas and quick rounds can be tried.");
					String err = WaveRuntime.trial(ctx.getSource().getServer(), a);
					if (err != null) return fail(ctx, err);
					ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": wave 1 comes in 3 seconds with nobody in it; it ends in 3 minutes."), true);
					return 1;
				})))
				.then(Commands.literal("edit").requires(admin).then(arenaArg.get()
						// Wave arenas: where players appear, the fight box, mob spawns, spectator area, exit pads, kit.
						.then(Commands.literal("spawn").executes(ctx -> edit(ctx, (a, p) -> {
							a.spawn = pos(p);
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
								.then(Commands.literal("clear").executes(ctx -> edit(ctx, (a, p) -> {
									a.spectate = null;
									return Games.quick(a.type) ? "No spectator area: players who step on the pad during a round try again after it, players who are out go back to the pad."
										: "No spectator area: the dead watch a teammate, queued parties wait at the pad.";
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
							a.kit = eu.explorerseden.nicecontrolcenter.claims.GameClaims.kitFrom(p);
							return "The kit is what you carry now (" + a.kit.size() + " stacks)" + ("own".equals(a.gear) ? "; it's handed out only with gear: kit (panel)." : ".");
						})))
						.then(Commands.literal("start").executes(ctx -> edit(ctx, (a, p) -> {
							a.start = pos(p);
							return "Start pad moved here.";
						})))
						.then(Commands.literal("top").executes(ctx -> edit(ctx, (a, p) -> {
							a.top = pos(p);
							return "Players drop from here now.";
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
						.then(Commands.literal("size").then(Commands.argument("cells", IntegerArgumentType.integer(4, 40)).executes(ctx -> edit(ctx, (a, p) -> {
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
							return "Maze built.";
						})))
						// Colour floor: the grid's corner at their feet (platforms go +X and +Z, the floor at this height).
						.then(Commands.literal("grid").executes(ctx -> edit(ctx, (a, p) -> {
							if (!a.type.equals("colorfloor")) return "Only colour floors have a grid.";
							Games.Pos at = pos(p);
							a.corner = new Games.Pos(at.dim, Math.floor(at.x), at.y - 1, Math.floor(at.z), 0);
							QuickRuntime.previewGrid((ServerLevel) p.level(), a);
							int span = a.platformSize * 3 + a.platformGap * 2;
							return "Grid built here: " + span + " × " + span + " blocks (+X and +Z, at the block under your feet). Keep the space below open: falling = out.";
						})))
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
		return text.replace("{player}", p.getGameProfile().name()).replace("{game}", a.shown()).replace("{score}", score);
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
		ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": " + msg), true);
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
		ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": " + msg), true);
		return 1;
	}

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
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
			case "waves" -> "This spot is the start point (a party leader stands on it). Then: /game edit " + name + " spawn (where players appear), corner1 / corner2 [x y z] (the fight box, height included),"
					+ " mobspawn add (where mobs come from; several), optionally spectate [radius] and exitpad add, and kit. Waves and rewards are set in the panel (Games).";
			case "dropper" -> "This spot is the start point. Stand where players drop from: /game edit " + name + " top; then in the water at the bottom: /game edit " + name + " finish <radius>.";
			case "ghasts" -> "This spot is the start point (everyone on it within " + a.joinSeconds + " s plays). Then: /game edit " + name + " spawn, corner1 / corner2 (the play box, height included),"
					+ " mobspawn add (where ghasts appear, up high; several). Try it with /game try " + name + ".";
			case "colorfloor" -> "This spot is the start point. Stand where the grid's corner goes and type /game edit " + name + " grid (it builds 3×3 platforms toward +X and +Z). Colours, speed and mobs are set in the panel.";
			case "anvils", "treasure", "fishing" -> "This spot is the start point. Then: /game edit " + name + " spawn and corner1 / corner2 (the play box, height included"
					+ (type.equals("anvils") ? "; anvils fall from its top" : type.equals("fishing") ? "; put water in it" : "; treasure appears on its floor") + "). Try it with /game try " + name + ".";
			case "heads" -> "Set the heads' skin: /game edit " + name + " texture <url or value>, then /game edit " + name + " headitem gives you heads to hide (placing one adds it)."
					+ " Rewards every few heads are set in the panel.";
			default -> "The maze is built from this corner (" + (a.size * 2 + 1) + " blocks each way, +X and +Z). The start point is here too; move it with /game edit " + name + " start.";
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
			case "fishing" -> a.timeLimit = 120;
			case "anvils" -> a.timeLimit = 300;
			default -> {
			}
		}
		if (type.equals("maze")) {
			a.corner = pos(p);
			a.start = new Games.Pos(a.corner.dim, a.corner.x - 2, a.corner.y, a.corner.z - 2, 0);
		}
		settings.arenas.add(a);
		settings.fill();
		saveSettings();
		if (type.equals("maze")) buildMaze((ServerLevel) p.level(), a);
		ctx.getSource().sendSuccess(() -> Component.literal("Made the " + type + " game " + name + ". " + next + (type.equals("heads") ? "" : " Then its entrance, e.g. in the hub: /game edit " + name + " entrance add (and a display name: /game edit " + name + " title …).")).withStyle(ChatFormatting.GREEN), true);
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
		ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": here are heads to hide; each one you place joins the hunt (break it in creative to take it out)."), false);
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
		ctx.getSource().sendSuccess(() -> Component.literal(a.shown() + ": " + msg), true);
		return done ? 1 : 0;
	}

	/** What the spectator area is for, by type. */
	private static String spectateUse(Arena a) {
		return Games.quick(a.type) ? "players who step on the pad during a round queue here for the next one, players who are out watch from here"
				: a.type.equals("waves") ? "the dead wait here, other parties queue here" : "only wave arenas and quick rounds use it";
	}

	private static Arena arena(CommandContext<CommandSourceStack> ctx) {
		return settings.arena(StringArgumentType.getString(ctx, "arena"));
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
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

	/** In a parkour, race, dropper or maze run. */
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
			else m.put("playing", sessions.values().stream().filter(s -> s.arena == a).count());
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
		long s = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
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

	/** Parkour blocks left by a crash: gone. */
	private static void clearLeftovers(MinecraftServer server) {
		placed.clear();
		try {
			if (placedFile == null || !Files.exists(placedFile)) return;
			String[] list = Json.GSON.fromJson(Files.readString(placedFile), String[].class);
			if (list == null) return;
			for (String key : list) {
				String[] k = key.split("\\|");
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
