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
 * Runs the mini-game arenas (see {@link Games}): stepping on an arena's start pad (or /game join) starts a run, an
 * action-bar line shows how it goes, and the end keeps a personal best and fires the Rewards triggers
 * "Game finished" and "New personal best". Templates: infinite parkour (jumps made ahead of you, each player in their
 * own lane), race course (checkpoints in order; running, boat or elytra), dropper (fall into the water at the bottom)
 * and maze (generated; made new daily or after each run).
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
		/** Parkour: its lane and the blocks lying ahead (oldest first). */
		int lane = -1;
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
	/** After a run ends, the start pad waits a moment before starting the next one. */
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

	private static void saveSettings() {
		try {
			Games.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the games: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Session s = sessions.get(p.getUUID());
			if (s == null) {
				if (!settings.enabled || cooldown.getOrDefault(p.getUUID(), 0L) > now) continue;
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
			if (!a.enabled || a.start == null || !a.start.dim.equals(dim)) continue;
			double dx = p.getX() - a.start.x, dz = p.getZ() - a.start.z, dy = p.getY() - a.start.y;
			if (dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5) return a;
		}
		return null;
	}

	private static void hud(ServerPlayer p, Session s) {
		String line = switch (s.arena.type) {
			case "parkour" -> "Jumps: " + s.progress + best(s, " · best ");
			case "race" -> clock(s.millis()) + " · checkpoint " + Math.min(s.progress, s.arena.checkpoints.size()) + "/" + s.arena.checkpoints.size() + best(s, " · best ");
			case "dropper" -> clock(s.millis()) + (s.progress > 0 ? " · try " + (s.progress + 1) : "") + best(s, " · best ");
			default -> clock(s.millis()) + best(s, " · best ");
		};
		p.sendOverlayMessage(Component.literal(s.arena.name + " · " + line).withStyle(ChatFormatting.AQUA));
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
				if (lane < 0) return "All " + a.lanes + " lanes are in use; try again in a moment.";
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
		p.sendSystemMessage(Component.literal(a.startMessage.isBlank() ? "▶ " + a.name + " started. " + hint + " /game leave stops." : fill(a.startMessage, a, p, "")).withStyle(ChatFormatting.GREEN));
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
		if (finished && p != null) {
			Map<String, Games.Score> board = scores.computeIfAbsent(s.arena.id, k -> new LinkedHashMap<>());
			Games.Score sc = board.computeIfAbsent(s.player.toString(), k -> new Games.Score());
			sc.name = p.getGameProfile().name();
			sc.runs++;
			boolean better = sc.at == 0 || (Games.higherIsBetter(s.arena.type) ? score > sc.best : score < sc.best);
			if (better) {
				sc.best = score;
				sc.at = System.currentTimeMillis();
				record = true;
			}
			saveScores();
		}
		if (p != null) {
			String result = Games.higherIsBetter(s.arena.type) ? s.progress + " jump" + (s.progress == 1 ? "" : "s") : clock(score);
			if (finished) {
				String line = s.arena.finishMessage.isBlank() ? "■ " + s.arena.name + ": " + result : fill(s.arena.finishMessage, s.arena, p, result);
				p.sendSystemMessage(Component.literal(line + (record ? " · new personal best!" : "")).withStyle(record ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
				commands(server, s.arena.finishCommands, s.arena, p, result);
			}
			else if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
			// Back to the start pad (not after dying: the respawn takes care of that).
			if (level != null && p.isAlive() && why != null && !why.startsWith("You left")) {
				p.teleportTo(level, s.arena.start.x, s.arena.start.y, s.arena.start.z, Set.of(), s.arena.start.yaw, 0, true);
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
		boolean[] used = new boolean[a.lanes];
		for (Session s : sessions.values()) if (s.arena == a && s.lane >= 0 && s.lane < a.lanes) used[s.lane] = true;
		for (int i = 0; i < a.lanes; i++) if (!used[i]) return i;
		return -1;
	}

	/** Lanes run side by side along X, each laneSpacing apart, starting a lane beyond the start pad. */
	private static BlockPos laneStart(Arena a, int lane) {
		return BlockPos.containing(a.start.x + (lane + 1) * a.laneSpacing, a.start.y, a.start.z);
	}

	private static void nextJump(ServerLevel level, Session s, Arena a) {
		BlockPos last = s.lastBlock;
		// Gaps grow with the score; never more than 4 forward or 1 up.
		int hard = Math.min(3, s.progress / 15);
		int dy = random.nextInt(3) - 1;
		int forward = 2 + random.nextInt(2) + (hard > 0 ? random.nextInt(hard + 1) / 2 : 0);
		if (dy > 0) forward = Math.min(forward, 3);
		forward = Math.min(forward, s.arena.maxGap);
		int side = random.nextInt(3) - 1;
		BlockPos lane = laneStart(a, s.lane);
		// Keep within the lane (laneSpacing wide) and within the world's height.
		int x = Math.max(lane.getX() - a.laneSpacing / 3, Math.min(lane.getX() + a.laneSpacing / 3, last.getX() + side));
		int y = Math.max(level.getMinY() + 4, Math.min(level.getMaxY() - 8, last.getY() + dy));
		place(level, s, new BlockPos(x, y, last.getZ() + forward), a);
	}

	private static void place(ServerLevel level, Session s, BlockPos pos, Arena a) {
		Block block = block(a.palette.get(random.nextInt(a.palette.size())));
		if (!level.getBlockState(pos).isAir()) pos = pos.above();
		level.setBlock(pos, block.defaultBlockState(), 3);
		s.blocks.addLast(pos);
		s.lastBlock = pos;
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
				}
				level.playSound(null, under, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6f, 1.2f + Math.min(0.8f, s.progress / 50f));
				return;
			}
			i++;
		}
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
					String err = start(ctx.getSource().getServer(), p, a);
					return err == null ? 1 : fail(ctx, err);
				})))
				.then(Commands.literal("leave").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Session s = sessions.get(p.getUUID());
					if (s == null) return fail(ctx, "You're not in a game.");
					end(ctx.getSource().getServer(), s, "You left the game.", false);
					return 1;
				}))
				.then(Commands.literal("top").then(arenaArg.get().executes(ctx -> {
					Arena a = arena(ctx);
					if (a == null) return fail(ctx, "There's no such game.");
					List<Games.Score> list = top(a, 10);
					StringBuilder b = new StringBuilder(a.name + (list.isEmpty() ? ": no runs yet." : ":"));
					int i = 1;
					for (Games.Score sc : list) b.append("\n").append(i++).append(". ").append(sc.name).append(" · ").append(Games.higherIsBetter(a.type) ? sc.best + " jumps" : clock(sc.best));
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				})))
				.then(Commands.literal("list").executes(ctx -> {
					String names = settings.arenas.stream().filter(a -> a.enabled).map(a -> a.name + " (" + a.type + ")").reduce((x, y) -> x + ", " + y).orElse("none yet");
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
					settings.arenas.remove(a);
					saveSettings();
					ctx.getSource().sendSuccess(() -> Component.literal("Removed " + a.name + " (the blocks it built stay)."), true);
					return 1;
				})))
				.then(Commands.literal("edit").requires(admin).then(arenaArg.get()
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
							if (!a.type.equals("maze") || a.corner == null) return "Only mazes are built.";
							buildMaze((ServerLevel) p.level(), a);
							return "Maze built.";
						}))))));
	}

	/** {player}, {game}, {score} in an arena's own lines and commands. */
	private static String fill(String text, Arena a, ServerPlayer p, String score) {
		return text.replace("{player}", p.getGameProfile().name()).replace("{game}", a.name).replace("{score}", score);
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
		ctx.getSource().sendSuccess(() -> Component.literal(a.name + ": " + msg), true);
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
			case "parkour" -> "Stepping on this spot starts a run; lanes go off along +X every " + a.laneSpacing + " blocks, so keep that space free.";
			case "race" -> "This spot is the start. Add checkpoints in order with /game edit " + name + " checkpoint add <radius>, then /game edit " + name + " finish <radius>.";
			case "dropper" -> "This spot is the start pad. Stand where players drop from: /game edit " + name + " top; then in the water at the bottom: /game edit " + name + " finish <radius>.";
			default -> "The maze is built from this corner (" + (a.size * 2 + 1) + " blocks each way, +X and +Z). The start pad is here too; move it with /game edit " + name + " start.";
		};
		if (type.equals("maze")) {
			a.corner = pos(p);
			a.start = new Games.Pos(a.corner.dim, a.corner.x - 2, a.corner.y, a.corner.z - 2, 0);
		}
		settings.arenas.add(a);
		settings.fill();
		saveSettings();
		if (type.equals("maze")) buildMaze((ServerLevel) p.level(), a);
		ctx.getSource().sendSuccess(() -> Component.literal("Made the " + type + " game " + name + ". " + next).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static Arena arena(CommandContext<CommandSourceStack> ctx) {
		return settings.arena(StringArgumentType.getString(ctx, "arena"));
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}

	// ---------------------------------------------------------------- scores and helpers

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
			m.put("playing", sessions.values().stream().filter(s -> s.arena == a).count());
			m.put("top", top(a, 10));
			out.add(m);
		}
		return out;
	}

	public static boolean resetScores(String arenaId) {
		boolean had = scores.remove(arenaId) != null;
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

	static ServerLevel levelOf(MinecraftServer server, String dim) {
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
			NiceControlCenter.LOGGER.warn("Could not clear old parkour blocks: {}", e.getMessage());
		}
	}

	private static void write(Path f, String text) {
		try {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save {}: {}", f.getFileName(), e.getMessage());
		}
	}
}
