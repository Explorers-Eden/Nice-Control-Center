package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Dungeons.Dungeon;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.core.AsyncFiles;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Runs the dungeons: the leader of a party (or a player on their own) standing on a pad gets a random dungeon from
 * the pad's pool, copied into a free slot of the dungeon dimension (slots sit slotSpacing blocks apart along +X).
 * The copy is pasted a few pieces per tick behind a boss bar, its markers spawn mobs and bosses, and the party is
 * brought to its start. Checkpoints set where they come back after a death (while lives last); out players watch
 * as spectators or go back. Reaching an exit (or every boss dead) clears it; afterwards the slot is wiped (also on
 * start-up, so no copy outlives a restart) and freed.
 */
public final class DungeonRuntime {
	public static final String PERMISSION = GameRuntime.PERMISSION;
	/** Where copies stand (their lowest corner's Y). */
	private static final int BASE_Y = 64;
	/** Ticks the leader stands on a pad before the run starts. */
	private static final int PAD_TICKS = 40;
	/** Party members this close to the pad come along. */
	private static final double PARTY_RANGE = 16;

	enum State { LOADING, PASTING, RUNNING, ENDING, WIPING }

	/** One copy of a dungeon and the players in it. */
	static final class Run {
		final int slot;
		final Dungeon dungeon;
		final Set<UUID> players = new LinkedHashSet<>();
		final Set<UUID> out = new LinkedHashSet<>();
		final Map<UUID, String> names = new HashMap<>();
		/** Deaths left before a player is out. */
		final Map<UUID, Integer> lives = new HashMap<>();
		/** Game modes of players who watch as spectators (given back afterwards). */
		final Map<UUID, GameType> watching = new HashMap<>();
		/** Players who died and come back inside (checkpoint) or watch: handled when they respawn. */
		final Set<UUID> respawnInside = new LinkedHashSet<>();
		State state = State.LOADING;
		CompletableFuture<List<DungeonTemplates.Piece>> loading;
		ArrayDeque<DungeonTemplates.Piece> toPaste;
		int total;
		/** Wiping: the pieces still to clear (dx, dz). */
		ArrayDeque<int[]> toWipe;
		BlockPos min;
		BlockPos max;
		BlockPos start;
		BlockPos checkpoint;
		final List<BlockPos> exits = new ArrayList<>();
		final List<BlockPos> checkpoints = new ArrayList<>();
		int bosses;
		float bossHealth;
		int deaths;
		long startedAt;
		long endAt;
		boolean cleared;
		ServerBossEvent bar;

		Run(int slot, Dungeon d) {
			this.slot = slot;
			this.dungeon = d;
		}

		long millis() {
			return startedAt == 0 ? 0 : (endAt > 0 && state != State.RUNNING ? endAt - (cleared ? 8000 : 3000) : System.currentTimeMillis()) - startedAt;
		}

		String bossTag() {
			return "ncc_dungeon_boss_" + slot;
		}

		boolean kit() {
			return "kit".equals(dungeon.gear);
		}
	}

	private static Dungeons.Settings settings = new Dungeons.Settings().fill();
	private static long modified = -1;
	private static final Map<Integer, Run> slots = new LinkedHashMap<>();
	/** player → the run they're in (also while they watch as spectators). */
	private static final Map<UUID, Run> playing = new HashMap<>();
	/** player → ticks the leader stood on a pad. */
	private static final Map<UUID, Integer> onPad = new HashMap<>();
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	/** player → the dungeon they played last (so the next pick is another when the pool has more). */
	private static final Map<UUID, String> lastPlayed = new HashMap<>();
	/** player → until when no grave is made (they died in a run). */
	private static final Map<UUID, Long> noGraveUntil = new HashMap<>();
	/** player → what to do once they've respawned after a run ended for them (give things back, send back). */
	private static final Set<UUID> afterRespawn = new LinkedHashSet<>();
	private static final Map<UUID, Boolean> afterRespawnRemove = new HashMap<>();
	/** Admins making a dungeon: name and the two corners. */
	private static final Map<UUID, Selection> selections = new HashMap<>();
	private static Map<String, Map<String, Dungeons.Best>> scores = new LinkedHashMap<>();
	private static Path scoresFile;
	private static Path slotsFile;
	private static final Random random = new Random();

	private record Selection(String name, String dim, BlockPos a, BlockPos b, long until) {
	}

	/** /dungeon create <name> [pos]: the first corner (given, looked at, or at their feet); the box is as high as its corners. */
	private static int corner1(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		BlockPos a = Corners.pick(ctx, p);
		selections.put(p.getUUID(), new Selection(name, p.level().dimension().identifier().toString(), a, null, System.currentTimeMillis() + 30 * 60_000L));
		Corners.show(p, a, null);
		ctx.getSource().sendSuccess(() -> Component.literal("Corner 1 of " + name + " is at " + a.toShortString() + ". Set the opposite corner (height included) with /dungeon corner2 [x y z]"
				+ " (or look at it), then /dungeon save " + name
				+ ". Inside, place data structure blocks as markers: start (required), exit, checkpoint, mob:<id> [xN] [{data}], boss:<id> [{data}].").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int corner2(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Selection s = selections.get(p.getUUID());
		if (s == null) return fail(ctx, "Start with /dungeon create <name> at the first corner.");
		if (!s.dim.equals(p.level().dimension().identifier().toString())) return fail(ctx, "Both corners have to be in the same dimension.");
		BlockPos b = Corners.pick(ctx, p);
		selections.put(p.getUUID(), new Selection(s.name, s.dim, s.a, b, System.currentTimeMillis() + 30 * 60_000L));
		Corners.show(p, s.a, b);
		ctx.getSource().sendSuccess(() -> Component.literal("Box: " + Corners.size(s.a, b) + " (corner 2 at " + b.toShortString() + "). /dungeon save " + s.name + " keeps it.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private DungeonRuntime() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Dungeons.FILE);
	}

	public static Dungeons.Settings settings() {
		return settings;
	}

	public static void register() {
		Parties.register();
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			slotsFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-dungeon-slots.json");
			scoresFile = server.getWorldPath(LevelResource.ROOT).resolve(Dungeons.SCORES);
			scores = Dungeons.readScores(scoresFile);
			wipeLeftovers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Run r : List.copyOf(slots.values())) {
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && playing.get(id) == r) leaveRun(server, r, p);
				}
				if (r.bar != null) r.bar.removeAllPlayers();
			}
			// What's in use stays in the slots file and is wiped on the next start.
		});
		ServerTickEvents.END_SERVER_TICK.register(DungeonRuntime::tick);
		// Before death drops: what they carry is put aside (own gear) or dealt with (kit), so nothing lies in the copy.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && playing.containsKey(p.getUUID())) died(p.level().getServer(), playing.get(p.getUUID()), p);
			return true;
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
			if (!alive) respawned(now.level().getServer(), now);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.player;
			Run r = playing.get(p.getUUID());
			if (r != null) out(server, r, p, null, true);
			else if (inDungeonDimension(p)) sendBack(server, p);
			onPad.remove(p.getUUID());
		});
		// Logged in inside the dungeon dimension without a run (a crash, a removed run): out at once.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			ServerPlayer p = handler.player;
			if (inDungeonDimension(p) && !playing.containsKey(p.getUUID())) sendBack(server, p);
		}));
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
		settings = Dungeons.read(file());
	}

	private static void saveSettings() {
		try {
			Dungeons.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the dungeons: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- for others

	/** In a dungeon run (a game area: no market, pets or inbox; the hub spawn takes over). */
	public static boolean inRun(ServerPlayer p) {
		return playing.containsKey(p.getUUID());
	}

	/** Died in a run a moment ago or still in one: no grave. */
	public static boolean noGrave(ServerPlayer p) {
		return inRun(p) || noGraveUntil.getOrDefault(p.getUUID(), 0L) > System.currentTimeMillis();
	}

	/** Dying now brings them back inside (lives left, or they'll watch): the hub spawn stays out of it. */
	public static boolean respawnsInside(ServerPlayer p) {
		Run r = playing.get(p.getUUID());
		if (r == null || r.state != State.RUNNING || r.out.contains(p.getUUID())) return false;
		return r.lives.getOrDefault(p.getUUID(), 0) > 0 || r.dungeon.outSpectate && r.players.size() - r.out.size() > 1;
	}

	public static boolean inDungeonDimension(ServerPlayer p) {
		return p.level().dimension().identifier().toString().equals(Dungeons.DIMENSION);
	}

	static ServerLevel level(MinecraftServer server) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(Dungeons.DIMENSION)));
	}

	/** For the panel: the runs going on and the best clears. */
	public static Map<String, Object> view() {
		List<Map<String, Object>> runs = new ArrayList<>();
		for (Run r : slots.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("slot", r.slot);
			m.put("dungeon", r.dungeon.name);
			m.put("state", r.state.name().toLowerCase(Locale.ROOT));
			List<Map<String, Object>> who = new ArrayList<>();
			for (UUID id : r.players) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("name", r.names.getOrDefault(id, "?"));
				p.put("lives", r.lives.getOrDefault(id, 0));
				p.put("out", r.out.contains(id));
				who.add(p);
			}
			m.put("players", who);
			m.put("millis", r.millis());
			runs.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runs", runs);
		out.put("scores", scores);
		return out;
	}

	/** From the panel: ends a run (everyone goes back). Returns an error or null. Server thread. */
	public static String close(MinecraftServer server, int slot) {
		Run r = slots.get(slot);
		if (r == null) return "That run is over already.";
		if (r.state == State.RUNNING) fail(server, r, "An admin closed this dungeon.");
		else if (r.state == State.LOADING || r.state == State.PASTING) fail(server, r, "An admin closed this dungeon.");
		return null;
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		if (t % 100 == 0) reload();
		for (Run r : List.copyOf(slots.values())) {
			try {
				step(server, r);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.error("Dungeon run in slot {} ({}) failed", r.slot, r.dungeon.name, e);
				fail(server, r, "Something went wrong with this dungeon; you're sent back.");
			}
		}
		if (t % 2 == 0 && settings.enabled) pads(server);
		if (t % 20 == 0) padParticles(server);
		if (t % 10 == 0) outlines(server);
		if (t % 1200 == 0) noGraveUntil.values().removeIf(until -> until < System.currentTimeMillis());
	}

	private static void step(MinecraftServer server, Run r) {
		ServerLevel level = level(server);
		if (level == null) return;
		switch (r.state) {
			case LOADING -> {
				if (!r.loading.isDone()) return;
				List<DungeonTemplates.Piece> pieces;
				try {
					pieces = r.loading.join();
				} catch (RuntimeException e) {
					fail(server, r, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
					return;
				}
				r.toPaste = new ArrayDeque<>(pieces);
				r.total = pieces.size();
				forceChunks(level, r, true);
				r.state = State.PASTING;
			}
			case PASTING -> {
				RandomSource rs = level.getRandom();
				for (int i = 0; i < settings.piecesPerTick && !r.toPaste.isEmpty(); i++) DungeonTemplates.paste(level, r.min, r.toPaste.poll(), rs);
				if (r.bar != null) r.bar.setProgress(r.total == 0 ? 1f : 1f - r.toPaste.size() / (float) r.total);
				if (r.toPaste.isEmpty()) begin(server, level, r);
			}
			case RUNNING -> running(server, level, r);
			case ENDING -> {
				if (System.currentTimeMillis() < r.endAt) return;
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && playing.get(id) == r) leaveRun(server, r, p);
					playing.remove(id);
				}
				r.players.clear();
				startWipe(r);
			}
			case WIPING -> {
				for (int i = 0; i < settings.piecesPerTick && !r.toWipe.isEmpty(); i++) {
					int[] at = r.toWipe.poll();
					DungeonTemplates.wipe(level, r.min, r.max, at[0], at[1]);
				}
				if (r.toWipe.isEmpty()) {
					forceChunks(level, r, false);
					slots.remove(r.slot);
					saveSlots();
				}
			}
		}
	}

	private static void running(MinecraftServer server, ServerLevel level, Run r) {
		int t = server.getTickCount();
		if (t % 5 != 0) return;
		for (UUID id : List.copyOf(r.players)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || r.out.contains(id) || !p.isAlive()) continue;
			if (p.level() != level || !inBox(r, p)) {
				out(server, r, p, "You left the dungeon: you're out of this run.", false);
				continue;
			}
			// A checkpoint someone reaches is where everyone comes back.
			for (BlockPos c : r.checkpoints) {
				if (!c.equals(r.checkpoint) && p.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5) < 4) {
					r.checkpoint = c;
					tellRun(server, r, Component.literal("✦ Checkpoint reached by " + p.getGameProfile().name()).withStyle(ChatFormatting.GOLD));
					level.playSound(null, c, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7f, 1.4f);
				}
			}
			if ("exit".equals(r.dungeon.clearOn)) {
				for (BlockPos e : r.exits) {
					if (p.distanceToSqr(e.getX() + 0.5, e.getY() + 0.5, e.getZ() + 0.5) < 2.25) {
						clear(server, r);
						return;
					}
				}
			}
			if (t % 10 == 0) hud(p, r);
		}
		watchers(server, r);
		if (r.bosses > 0 && t % 10 == 0) bosses(server, level, r);
		if (r.state != State.RUNNING) return;
		if (r.dungeon.timeLimit > 0 && r.millis() > r.dungeon.timeLimit * 60_000L) {
			fail(server, r, "Time's up.");
			return;
		}
		if (r.players.stream().allMatch(r.out::contains)) fail(server, r, null);
	}

	/** Out players watch through a teammate who's still in (no flying around): kept on one, the next when they're out. */
	private static void watchers(MinecraftServer server, Run r) {
		List<ServerPlayer> in = new ArrayList<>();
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && !r.out.contains(id) && p.isAlive()) in.add(p);
		}
		Spectating.follow(server, r.watching.keySet(), in);
	}

	/** The boss bar follows the bosses' health; every boss dead clears a "boss" dungeon. */
	private static void bosses(MinecraftServer server, ServerLevel level, Run r) {
		AABB box = new AABB(r.min.getX() - 8, r.min.getY() - 32, r.min.getZ() - 8, r.max.getX() + 9, r.max.getY() + 33, r.max.getZ() + 9);
		List<LivingEntity> alive = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e.entityTags().contains(r.bossTag()));
		if (r.bossHealth <= 0) {
			for (LivingEntity e : alive) r.bossHealth += e.getMaxHealth();
		}
		float now = 0;
		for (LivingEntity e : alive) now += e.getHealth();
		if (r.bar == null && !alive.isEmpty()) {
			r.bar = new ServerBossEvent(UUID.randomUUID(), alive.get(0).getDisplayName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
		}
		if (r.bar != null) {
			r.bar.setProgress(r.bossHealth <= 0 ? 0 : Math.max(0, Math.min(1, now / r.bossHealth)));
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && p.level() == level) r.bar.addPlayer(p);
			}
		}
		if (alive.isEmpty() && r.bossHealth > 0) {
			r.bosses = 0;
			if (r.bar != null) r.bar.removeAllPlayers();
			r.bar = null;
			if ("boss".equals(r.dungeon.clearOn)) clear(server, r);
			else tellRun(server, r, Component.literal("The boss is down!").withStyle(ChatFormatting.GOLD));
		}
	}

	private static boolean inBox(Run r, ServerPlayer p) {
		return p.getX() >= r.min.getX() - 3 && p.getX() <= r.max.getX() + 4 && p.getZ() >= r.min.getZ() - 3 && p.getZ() <= r.max.getZ() + 4
				&& p.getY() >= r.min.getY() - 24 && p.getY() <= r.max.getY() + 24;
	}

	private static void hud(ServerPlayer p, Run r) {
		long left = r.players.size() - r.out.size();
		String limit = r.dungeon.timeLimit > 0 ? " / " + GameRuntime.clock(r.dungeon.timeLimit * 60_000L) : "";
		int lives = r.lives.getOrDefault(p.getUUID(), 0);
		p.sendOverlayMessage(Component.literal(r.dungeon.name + " · " + GameRuntime.clock(r.millis()) + limit
				+ (r.dungeon.lives > 0 ? " · " + "❤".repeat(Math.min(lives, 10)) + (lives == 0 ? "last life" : "") : "")
				+ (r.players.size() > 1 ? " · " + left + " in" : "")).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private static void tellRun(MinecraftServer server, Run r, Component text) {
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) p.sendSystemMessage(text);
		}
	}

	// ---------------------------------------------------------------- pads and starting

	private static void pads(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Dungeons.Pad pad = padUnder(p);
			if (pad == null || playing.containsKey(p.getUUID()) || p.isSpectator() || cooldown.getOrDefault(p.getUUID(), 0L) > now) {
				onPad.remove(p.getUUID());
				continue;
			}
			if (!Parties.isLeader(p.getUUID())) {
				if (onPad.merge(p.getUUID(), 2, Integer::sum) % 40 == 2) p.sendOverlayMessage(Component.literal("Your party leader starts the dungeon from the pad").withStyle(ChatFormatting.GRAY));
				continue;
			}
			List<ServerPlayer> party = partyNear(server, p, pad);
			int ticks = onPad.merge(p.getUUID(), 2, Integer::sum);
			if (ticks < PAD_TICKS) {
				if (ticks % 20 == 2) {
					String who = party.size() == 1 ? "" : " with " + String.join(", ", party.subList(1, party.size()).stream().map(x -> x.getGameProfile().name()).toList());
					p.sendOverlayMessage(Component.literal("Dungeon (" + pad.pool + ")" + who + " in " + ((PAD_TICKS - ticks) / 20 + 1) + "… step off to cancel").withStyle(ChatFormatting.LIGHT_PURPLE));
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
				}
				continue;
			}
			onPad.remove(p.getUUID());
			cooldown.put(p.getUUID(), now + 5000);
			List<UUID> missing = new ArrayList<>(Parties.members(p.getUUID()));
			party.forEach(x -> missing.remove(x.getUUID()));
			String err = start(server, party, pad.pool);
			if (err != null) {
				p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
			} else if (!missing.isEmpty()) {
				for (UUID id : missing) {
					ServerPlayer m = server.getPlayerList().getPlayer(id);
					if (m != null) m.sendSystemMessage(Component.literal("Your party went into a dungeon without you (be within " + (int) PARTY_RANGE + " blocks of the pad).").withStyle(ChatFormatting.GRAY));
				}
			}
		}
	}

	/** The leader and the party members close to the pad who can come along. */
	private static List<ServerPlayer> partyNear(MinecraftServer server, ServerPlayer leader, Dungeons.Pad pad) {
		List<ServerPlayer> out = new ArrayList<>();
		out.add(leader);
		long now = System.currentTimeMillis();
		for (UUID id : Parties.members(leader.getUUID())) {
			if (id.equals(leader.getUUID())) continue;
			ServerPlayer m = server.getPlayerList().getPlayer(id);
			if (m == null || m.level() != leader.level() || playing.containsKey(id) || m.isSpectator() || cooldown.getOrDefault(id, 0L) > now) continue;
			if (m.distanceToSqr(pad.pos.x, pad.pos.y, pad.pos.z) <= PARTY_RANGE * PARTY_RANGE) out.add(m);
		}
		return out;
	}

	private static Dungeons.Pad padUnder(ServerPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		for (Dungeons.Pad pad : settings.pads) {
			if (!pad.pos.dim.equals(dim)) continue;
			double dx = p.getX() - pad.pos.x, dz = p.getZ() - pad.pos.z, dy = p.getY() - pad.pos.y;
			if (dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5) return pad;
		}
		return null;
	}

	private static void padParticles(MinecraftServer server) {
		for (Dungeons.Pad pad : settings.pads) {
			ServerLevel level = GameRuntime.levelOf(server, pad.pos.dim);
			if (level == null || level.getNearestPlayer(pad.pos.x, pad.pos.y, pad.pos.z, 24, false) == null) continue;
			for (int i = 0; i < 8; i++) {
				double a = i * Math.PI / 4 + server.getTickCount() / 20.0 * 0.4;
				level.sendParticles(ParticleTypes.WITCH, pad.pos.x + Math.cos(a) * 0.8, pad.pos.y + 0.15, pad.pos.z + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
			}
		}
	}

	/** Starts a run for these players with a random dungeon of the pool; returns why not, or null. */
	static String start(MinecraftServer server, List<ServerPlayer> party, String pool) {
		if (!settings.enabled) return "Dungeons are closed right now.";
		int size = party.size();
		List<Dungeon> fits = new ArrayList<>();
		for (Dungeon d : settings.dungeons) {
			if (d.enabled && d.saved() && d.pool.equals(pool) && size >= d.minParty && size <= d.maxParty && d.hasMarker("start")) fits.add(d);
		}
		if (fits.isEmpty()) return "No dungeon in the " + pool + " pool is ready for " + (size == 1 ? "one player" : size + " players") + " yet.";
		String last = lastPlayed.get(party.get(0).getUUID());
		if (fits.size() > 1 && last != null) fits.removeIf(d -> d.id.equals(last));
		return start(server, party, fits.get(random.nextInt(fits.size())));
	}

	/** A run of this dungeon (no players: a copy that stands for a minute, for trying it from the console). */
	static String start(MinecraftServer server, List<ServerPlayer> party, Dungeon d) {
		ServerLevel level = level(server);
		if (level == null) return "The dungeon dimension isn't loaded; it appears after the server restarts with this version.";
		if (!d.saved()) return d.name + " has no saved blocks yet: /dungeon save " + d.name;
		if (!d.hasMarker("start")) return d.name + " needs a start marker (a data structure block with the text start).";
		int slot = -1;
		for (int i = 0; i < settings.maxSlots; i++) {
			if (!slots.containsKey(i)) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return "Every dungeon copy is in use right now; try again in a moment.";
		Run r = new Run(slot, d);
		int x = (slot + 1) * settings.slotSpacing;
		r.min = new BlockPos(x, BASE_Y, 0);
		r.max = r.min.offset(d.sizeX - 1, d.sizeY - 1, d.sizeZ - 1);
		r.loading = DungeonTemplates.load(server, d);
		r.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(fill(d.prepareMessage.isEmpty() ? "Preparing your dungeon…" : d.prepareMessage, r, null)),
				BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
		r.bar.setProgress(0);
		for (ServerPlayer p : party) {
			r.players.add(p.getUUID());
			r.names.put(p.getUUID(), p.getGameProfile().name());
			r.lives.put(p.getUUID(), d.lives);
			playing.put(p.getUUID(), r);
			lastPlayed.put(p.getUUID(), d.id);
			r.bar.addPlayer(p);
		}
		slots.put(slot, r);
		saveSlots();
		return null;
	}

	/** The copy is ready: markers become air (spawning what they say), everyone goes to the start. */
	private static void begin(MinecraftServer server, ServerLevel level, Run r) {
		for (Dungeons.Marker m : r.dungeon.markers) {
			BlockPos at = r.min.offset(m.x, m.y, m.z);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
			switch (m.type) {
				case "start" -> {
					if (r.start == null) r.start = at;
				}
				case "exit" -> r.exits.add(at);
				case "checkpoint" -> r.checkpoints.add(at);
				case "mob" -> spawn(server, r, at, m.arg, false);
				case "boss" -> {
					spawn(server, r, at, m.arg, true);
					r.bosses++;
				}
				default -> {
				}
			}
		}
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
		r.state = State.RUNNING;
		r.startedAt = System.currentTimeMillis();
		if (r.players.isEmpty()) {
			// Tried from the console: it stands for a minute, then it's wiped.
			NiceControlCenter.LOGGER.info("Dungeon {} copied into slot {} at {} (start {}); wiped in a minute", r.dungeon.name, r.slot, r.min.toShortString(), r.start.toShortString());
			r.state = State.ENDING;
			r.endAt = System.currentTimeMillis() + 60_000;
			return;
		}
		for (UUID id : List.copyOf(r.players)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				r.out.add(id);
				playing.remove(id);
				continue;
			}
			if (r.kit()) {
				GameClaims.stashFor(p, "dungeon");
				GameClaims.giveKit(p, r.dungeon.kit, "dungeon");
			}
			p.teleportTo(level, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
			p.resetFallDistance();
			GameClaims.gameMode(p, false);
			p.sendSystemMessage(Component.literal(fill(r.dungeon.enterMessage.isEmpty() ? "▶ {dungeon}: find the way out!" : r.dungeon.enterMessage, r, p)).withStyle(ChatFormatting.LIGHT_PURPLE));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(r.dungeon.name).withStyle(ChatFormatting.LIGHT_PURPLE)));
			commands(server, r.dungeon.enterCommands, r, p);
		}
		level.playSound(null, r.start, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1f, 0.8f);
	}

	/**
	 * A mob or boss marker: "zombie", "minecraft:skeleton x3", "husk x2 {CustomName:'Guard'}". Summoned like the
	 * /summon command (so its data works the same everywhere), kept from despawning; bosses get the run's tag.
	 */
	private static void spawn(MinecraftServer server, Run r, BlockPos at, String arg, boolean boss) {
		MobLines.Entry e = MobLines.parse(arg);
		if (e == null) return;
		int count = boss ? 1 : e.count();
		for (int i = 0; i < count; i++) {
			double ox = count > 1 ? (random.nextDouble() - 0.5) * 2 : 0, oz = count > 1 ? (random.nextDouble() - 0.5) * 2 : 0;
			MobLines.summon(server, Dungeons.DIMENSION, at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz, e, boss ? "Tags:[\"" + r.bossTag() + "\"]" : "");
		}
	}

	// ---------------------------------------------------------------- deaths

	/** Before the death: what they carry is put aside (own gear) or dealt with (kit); lives and watching decided. */
	private static void died(MinecraftServer server, Run r, ServerPlayer p) {
		noGraveUntil.put(p.getUUID(), System.currentTimeMillis() + 60_000);
		// Found items are kept only when a life brings them back in (not when they watch or are out).
		boolean keep = r.state == State.RUNNING && !r.out.contains(p.getUUID()) && r.lives.getOrDefault(p.getUUID(), 0) > 0;
		if (r.kit()) GameClaims.clearCarried(p, keep);
		else GameClaims.stashFor(p, "dungeon-death");
		if (r.state != State.RUNNING || r.out.contains(p.getUUID())) return;
		r.deaths++;
		boolean inside = respawnsInside(p);
		int lives = r.lives.getOrDefault(p.getUUID(), 0);
		if (lives > 0) {
			r.lives.put(p.getUUID(), lives - 1);
			r.respawnInside.add(p.getUUID());
			tellRun(server, r, Component.literal(p.getGameProfile().name() + " died" + (lives - 1 == 0 ? " (last life left)" : " (" + (lives - 1) + " lives left)")).withStyle(ChatFormatting.RED));
			return;
		}
		// Out: they watch (when others are still in) or go back once they've respawned.
		r.out.add(p.getUUID());
		cooldown.put(p.getUUID(), System.currentTimeMillis() + settings.cooldown * 1000L);
		commands(server, r.dungeon.exitCommands, r, p);
		if (inside) {
			r.respawnInside.add(p.getUUID());
			tellRun(server, r, Component.literal(p.getGameProfile().name() + " is out and watches.").withStyle(ChatFormatting.GRAY));
		} else {
			playing.remove(p.getUUID());
			afterRespawn.add(p.getUUID());
			afterRespawnRemove.put(p.getUUID(), r.dungeon.removePickups);
			p.sendSystemMessage(Component.literal("You're out of this run.").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void respawned(MinecraftServer server, ServerPlayer p) {
		boolean ours = afterRespawn.contains(p.getUUID()) || playing.containsKey(p.getUUID());
		try {
			respawnedInside(server, p);
		} finally {
			// Unless they went back in (given back there), what they found is gone (only for dungeon players).
			if (ours) GameClaims.forgetKept(p);
		}
	}

	private static void respawnedInside(MinecraftServer server, ServerPlayer p) {
		if (afterRespawn.remove(p.getUUID())) {
			// Out of the run: things back, and out of the dungeon dimension (the hub spawn may have done that already).
			GameClaims.restoreFor(p, Boolean.TRUE.equals(afterRespawnRemove.remove(p.getUUID())));
			if (inDungeonDimension(p)) sendBack(server, p);
			return;
		}
		Run r = playing.get(p.getUUID());
		if (r == null || !r.respawnInside.remove(p.getUUID())) return;
		ServerLevel level = level(server);
		if (level == null || r.state != State.RUNNING) {
			leaveRun(server, r, p);
			playing.remove(p.getUUID());
			return;
		}
		BlockPos to = r.checkpoint != null ? r.checkpoint : r.start;
		p.teleportTo(level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
		if (r.out.contains(p.getUUID())) {
			r.watching.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
			p.setGameMode(GameType.SPECTATOR);
			p.sendSystemMessage(Component.literal("You're out: you watch your party until the run ends.").withStyle(ChatFormatting.GRAY));
			watchers(server, r);
			return;
		}
		// Back in: their own gear (own) or the kit again.
		if (r.kit()) {
			GameClaims.giveKit(p, r.dungeon.kit, "dungeon");
			GameClaims.giveBackKept(p);
		} else {
			GameClaims.restoreFor(p, false);
		}
	}

	// ---------------------------------------------------------------- ending

	/** A player is out (left, logged out, /dungeon leave); the run carries on for the others. */
	private static void out(MinecraftServer server, Run r, ServerPlayer p, String why, boolean loggedOut) {
		if (r.state == State.LOADING || r.state == State.PASTING) {
			// Not in yet: they simply don't come along.
			r.players.remove(p.getUUID());
			playing.remove(p.getUUID());
			if (r.bar != null) r.bar.removePlayer(p);
			if (r.players.isEmpty()) startWipe(r);
			return;
		}
		boolean wasOut = r.out.contains(p.getUUID());
		r.out.add(p.getUUID());
		r.respawnInside.remove(p.getUUID());
		if (!wasOut) {
			cooldown.put(p.getUUID(), System.currentTimeMillis() + settings.cooldown * 1000L);
			commands(server, r.dungeon.exitCommands, r, p);
		}
		if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
		if (p.isAlive()) {
			leaveRun(server, r, p);
		} else {
			// Dead: done once they respawn.
			afterRespawn.add(p.getUUID());
			afterRespawnRemove.put(p.getUUID(), r.dungeon.removePickups);
		}
		playing.remove(p.getUUID());
	}

	/** Out of the run for good: their game mode and things back, and out of the dungeon dimension. */
	private static void leaveRun(MinecraftServer server, Run r, ServerPlayer p) {
		GameType mode = r.watching.remove(p.getUUID());
		if (mode != null) {
			p.setCamera(p);
			p.setGameMode(mode);
		}
		GameClaims.restoreFor(p, r.dungeon.removePickups);
		if (r.bar != null) r.bar.removePlayer(p);
		if (inDungeonDimension(p)) sendBack(server, p);
	}

	private static void clear(MinecraftServer server, Run r) {
		r.cleared = true;
		long time = System.currentTimeMillis() - r.startedAt;
		int size = r.players.size();
		Map<String, Dungeons.Best> board = scores.computeIfAbsent(r.dungeon.id, k -> new LinkedHashMap<>());
		Dungeons.Best best = board.computeIfAbsent(String.valueOf(size), k -> new Dungeons.Best());
		best.clears++;
		boolean record = best.millis == 0 || time < best.millis;
		if (record) {
			best.millis = time;
			best.names = new ArrayList<>(r.names.values());
			best.at = System.currentTimeMillis();
		}
		if (scoresFile != null) AsyncFiles.write(scoresFile, Json.GSON.toJson(scores));
		end(server, r, (r.dungeon.clearedMessage.isEmpty() ? "■ {dungeon} cleared in {time}!" : r.dungeon.clearedMessage) + (record ? " A new best for " + (size == 1 ? "one player" : size + " players") + "!" : ""), ChatFormatting.GOLD);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) RewardsControl.fireGame(server, "dungeonCleared", p, r.dungeon.name, time, true);
		}
	}

	private static void fail(MinecraftServer server, Run r, String why) {
		if (r.state == State.LOADING || r.state == State.PASTING) {
			for (UUID id : r.players) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
				playing.remove(id);
			}
			r.players.clear();
			startWipe(r);
			return;
		}
		if (r.state != State.RUNNING) return;
		String line = r.dungeon.failedMessage.isEmpty() ? "■ {dungeon}: the run is over." : r.dungeon.failedMessage;
		end(server, r, why == null ? line : why + " " + line, ChatFormatting.RED);
		long time = System.currentTimeMillis() - r.startedAt;
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) RewardsControl.fireGame(server, "dungeonFailed", p, r.dungeon.name, time, true);
		}
	}

	/** Tells everyone, runs the exit commands for those still in, and sends them back after a few seconds. */
	private static void end(MinecraftServer server, Run r, String message, ChatFormatting color) {
		r.endAt = System.currentTimeMillis() + (r.cleared ? 8000 : 3000);
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			p.sendSystemMessage(Component.literal(fill(message, r, p)).withStyle(color));
			if (!r.out.contains(id)) {
				commands(server, r.dungeon.exitCommands, r, p);
				cooldown.put(id, System.currentTimeMillis() + settings.cooldown * 1000L);
			}
			if (r.cleared) p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1f);
		}
		r.state = State.ENDING;
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
	}

	private static void startWipe(Run r) {
		if (r.bar != null) r.bar.removeAllPlayers();
		r.bar = null;
		r.state = State.WIPING;
		r.toWipe = new ArrayDeque<>();
		for (int dx = 0; dx <= r.max.getX() - r.min.getX(); dx += Dungeons.PIECE) {
			for (int dz = 0; dz <= r.max.getZ() - r.min.getZ(); dz += Dungeons.PIECE) r.toWipe.add(new int[] {dx, dz});
		}
	}

	/** Out of the dungeon dimension: the hub spawn, else where they'd respawn. */
	static void sendBack(MinecraftServer server, ServerPlayer p) {
		if (GameRuntime.toHub(server, p)) return;
		var to = p.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
		p.teleportTo(to.newLevel(), to.position().x, to.position().y, to.position().z, Set.of(), to.yRot(), to.xRot(), true);
	}

	private static void forceChunks(ServerLevel level, Run r, boolean on) {
		for (int cx = r.min.getX() >> 4; cx <= r.max.getX() >> 4; cx++) {
			for (int cz = r.min.getZ() >> 4; cz <= r.max.getZ() >> 4; cz++) level.setChunkForced(cx, cz, on);
		}
	}

	private static String fill(String text, Run r, ServerPlayer p) {
		String party = String.join(", ", r.names.values());
		return text.replace("{dungeon}", r.dungeon.name).replace("{party}", party).replace("{time}", GameRuntime.clock(r.millis()))
				.replace("{deaths}", String.valueOf(r.deaths)).replace("{player}", p == null ? party : p.getGameProfile().name());
	}

	private static void commands(MinecraftServer server, List<String> lines, Run r, ServerPlayer p) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), r, p);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Dungeon");
		}
	}

	// ---------------------------------------------------------------- slots on disk

	/** The slots in use, so a crash or restart never leaves a copy standing. Written on the file worker. */
	private static void saveSlots() {
		if (slotsFile == null) return;
		List<int[]> boxes = new ArrayList<>();
		for (Run r : slots.values()) boxes.add(new int[] {r.slot, r.min.getX(), r.min.getY(), r.min.getZ(), r.max.getX(), r.max.getY(), r.max.getZ()});
		AsyncFiles.write(slotsFile, Json.GSON.toJson(boxes));
	}

	private static void wipeLeftovers(MinecraftServer server) {
		slots.clear();
		playing.clear();
		try {
			if (slotsFile == null || !Files.exists(slotsFile)) return;
			int[][] boxes = Json.GSON.fromJson(Files.readString(slotsFile), int[][].class);
			if (boxes == null) return;
			for (int[] b : boxes) {
				if (b.length < 7) continue;
				Dungeon placeholder = new Dungeon();
				placeholder.name = "leftover";
				Run r = new Run(b[0], placeholder);
				r.min = new BlockPos(b[1], b[2], b[3]);
				r.max = new BlockPos(b[4], b[5], b[6]);
				startWipe(r);
				ServerLevel level = level(server);
				if (level != null) forceChunks(level, r, true);
				slots.put(r.slot, r);
			}
			if (!slots.isEmpty()) NiceControlCenter.LOGGER.info("Wiping {} dungeon cop{} left from before the restart", slots.size(), slots.size() == 1 ? "y" : "ies");
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read the dungeon slots: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- building (admins)

	private static void outlines(MinecraftServer server) {
		long now = System.currentTimeMillis();
		selections.entrySet().removeIf(e -> e.getValue().until < now);
		for (Map.Entry<UUID, Selection> e : selections.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Selection s = e.getValue();
			if (p == null || s.a == null || !p.level().dimension().identifier().toString().equals(s.dim)) continue;
			BlockPos b = s.b == null ? p.blockPosition() : s.b;
			int x0 = Math.min(s.a.getX(), b.getX()), y0 = Math.min(s.a.getY(), b.getY()), z0 = Math.min(s.a.getZ(), b.getZ());
			int x1 = Math.max(s.a.getX(), b.getX()) + 1, y1 = Math.max(s.a.getY(), b.getY()) + 1, z1 = Math.max(s.a.getZ(), b.getZ()) + 1;
			DustParticleOptions dust = new DustParticleOptions(0xB45CFF, 1.2f);
			ServerLevel level = (ServerLevel) p.level();
			// The twelve edges, a particle every couple of blocks (only the admin sees them).
			int step = Math.max(1, Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0)) / 40);
			for (int x = x0; x <= x1; x += step) for (int[] yz : new int[][] {{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) level.sendParticles(p, dust, true, false, x, yz[0], yz[1], 1, 0, 0, 0, 0);
			for (int y = y0; y <= y1; y += step) for (int[] xz : new int[][] {{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) level.sendParticles(p, dust, true, false, xz[0], y, xz[1], 1, 0, 0, 0, 0);
			for (int z = z0; z <= z1; z += step) for (int[] xy : new int[][] {{x0, y0}, {x0, y1}, {x1, y0}, {x1, y1}}) level.sendParticles(p, dust, true, false, xy[0], xy[1], z, 1, 0, 0, 0, 0);
		}
	}

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		var dungeonArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("name", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.dungeons.stream().map(x -> x.name), b));
		var poolArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("pool", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.pools(), b));
		d.register(Commands.literal("dungeon").requires(Everyone.unless("nicecontrolcenter.dungeons.use"))
				.then(Commands.literal("leave").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Run r = playing.get(p.getUUID());
					if (r == null) return fail(ctx, "You're not in a dungeon.");
					out(ctx.getSource().getServer(), r, p, "You left the dungeon.", false);
					return 1;
				}))
				.then(Commands.literal("list").executes(ctx -> {
					StringBuilder b = new StringBuilder();
					for (String pool : settings.pools()) {
						List<String> names = settings.dungeons.stream().filter(x -> x.pool.equals(pool)).map(x -> x.name + (x.enabled && x.saved() ? "" : " (off)")).toList();
						long pads = settings.pads.stream().filter(x -> x.pool.equals(pool)).count();
						b.append("\n").append(pool).append(": ").append(names.isEmpty() ? "no dungeons" : String.join(", ", names)).append(" · ").append(pads).append(pads == 1 ? " pad" : " pads");
					}
					ctx.getSource().sendSuccess(() -> Component.literal("Dungeon pools:" + (b.isEmpty() ? " none yet." : b)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
					return 1;
				}))
				.then(Commands.literal("top").then(dungeonArg.get().executes(ctx -> {
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"));
					if (x == null) return fail(ctx, "There's no such dungeon.");
					Map<String, Dungeons.Best> board = scores.getOrDefault(x.id, Map.of());
					StringBuilder b = new StringBuilder(x.name + (board.isEmpty() ? ": not cleared yet." : ":"));
					for (int n = 1; n <= 4; n++) {
						Dungeons.Best best = board.get(String.valueOf(n));
						if (best != null) b.append("\n").append(n == 1 ? "Solo" : n + " players").append(": ").append(GameRuntime.clock(best.millis)).append(" · ").append(String.join(", ", best.names));
					}
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				})))
				.then(Commands.literal("create").requires(admin).then(Commands.argument("name", StringArgumentType.word()).executes(DungeonRuntime::corner1)
						.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::corner1))))
				.then(Commands.literal("corner2").requires(admin).executes(DungeonRuntime::corner2)
						.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::corner2)))
				.then(Commands.literal("save").requires(admin).then(dungeonArg.get().executes(DungeonRuntime::save)
						.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
								.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::save)))))
				.then(Commands.literal("kit").requires(admin).then(dungeonArg.get().executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"));
					if (x == null) return fail(ctx, "There's no such dungeon.");
					x.kit = GameClaims.kitFrom(p);
					saveSettings();
					ctx.getSource().sendSuccess(() -> Component.literal(x.name + "'s kit is what you carry now (" + x.kit.size() + " stacks)."
							+ ("own".equals(x.gear) ? " It's handed out only when the dungeon puts things aside (gear: kit, in the panel)." : "")), true);
					return 1;
				})))
				.then(Commands.literal("try").requires(admin).then(dungeonArg.get().executes(ctx -> {
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"));
					if (x == null) return fail(ctx, "There's no such dungeon.");
					ServerPlayer p = ctx.getSource().getPlayer();
					if (p != null && playing.containsKey(p.getUUID())) return fail(ctx, "You're in a dungeon already: /dungeon leave first.");
					String err = start(ctx.getSource().getServer(), p == null ? List.of() : List.of(p), x);
					if (err != null) return fail(ctx, err);
					ctx.getSource().sendSuccess(() -> Component.literal("Copying " + x.name + "…"), true);
					return 1;
				})))
				.then(Commands.literal("delete").requires(admin).then(dungeonArg.get().executes(ctx -> {
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"));
					if (x == null) return fail(ctx, "There's no such dungeon.");
					settings.dungeons.remove(x);
					saveSettings();
					DungeonTemplates.delete(ctx.getSource().getServer(), x.name);
					ctx.getSource().sendSuccess(() -> Component.literal("Removed " + x.name + " (runs already going keep their copy)."), true);
					return 1;
				})))
				.then(Commands.literal("pool").requires(admin).then(dungeonArg.get().then(poolArg.get().executes(ctx -> {
					Dungeon x = settings.dungeon(StringArgumentType.getString(ctx, "name"));
					if (x == null) return fail(ctx, "There's no such dungeon.");
					x.pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
					saveSettings();
					ctx.getSource().sendSuccess(() -> Component.literal(x.name + " is in the " + x.pool + " pool now."), true);
					return 1;
				}))))
				.then(Commands.literal("pad").requires(admin)
						.then(Commands.literal("add").then(poolArg.get().executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							Dungeons.Pad pad = new Dungeons.Pad();
							pad.pool = Dungeons.pool(StringArgumentType.getString(ctx, "pool"));
							pad.pos = new Games.Pos(p.level().dimension().identifier().toString(), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, 0);
							settings.pads.add(pad);
							settings.fill();
							saveSettings();
							ctx.getSource().sendSuccess(() -> Component.literal("Pad for the " + pad.pool + " pool here: the party leader standing on it for two seconds starts a run."), true);
							return 1;
						})))
						.then(Commands.literal("remove").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							String dim = p.level().dimension().identifier().toString();
							Dungeons.Pad near = null;
							double best = 16;
							for (Dungeons.Pad pad : settings.pads) {
								if (!pad.pos.dim.equals(dim)) continue;
								double dist = p.distanceToSqr(pad.pos.x, pad.pos.y, pad.pos.z);
								if (dist < best) {
									best = dist;
									near = pad;
								}
							}
							if (near == null) return fail(ctx, "No pad within 4 blocks.");
							settings.pads.remove(near);
							saveSettings();
							ctx.getSource().sendSuccess(() -> Component.literal("Pad removed."), true);
							return 1;
						})))
				.then(Commands.literal("runs").requires(admin).executes(ctx -> {
					StringBuilder b = new StringBuilder();
					for (Run r : slots.values()) b.append("\nslot ").append(r.slot).append(": ").append(r.dungeon.name).append(" · ").append(r.state.name().toLowerCase(Locale.ROOT)).append(" · ").append(String.join(", ", r.names.values()));
					ctx.getSource().sendSuccess(() -> Component.literal(b.isEmpty() ? "No dungeon runs right now." : "Dungeon runs:" + b), false);
					return 1;
				})));
	}

	private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayer();
		MinecraftServer server = ctx.getSource().getServer();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
		Selection s = p == null ? null : selections.get(p.getUUID());
		Dungeon d = settings.dungeon(name);
		ServerLevel level;
		BlockPos a, b;
		boolean corners = ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("to"));
		if (corners) {
			level = ctx.getSource().getLevel();
			a = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from");
			b = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to");
		} else if (s != null && s.name.equalsIgnoreCase(name) && s.b != null) {
			level = GameRuntime.levelOf(server, s.dim);
			a = s.a;
			b = s.b;
		} else if (d != null && d.from != null && d.to != null && d.sourceDim != null) {
			// Saving again after changes: the box it was made from.
			level = GameRuntime.levelOf(server, d.sourceDim);
			a = new BlockPos(d.from[0], d.from[1], d.from[2]);
			b = new BlockPos(d.to[0], d.to[1], d.to[2]);
		} else {
			return fail(ctx, "Mark the box first: /dungeon create " + name + " at one corner, /dungeon corner2 at the other.");
		}
		if (level == null) return fail(ctx, "That dimension isn't loaded.");
		long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
		if (volume > 16_000_000L) return fail(ctx, "That box is too big (" + String.format(Locale.ROOT, "%,d", volume) + " blocks; at most 16 million).");
		boolean fresh = d == null;
		Dungeon target = fresh ? new Dungeon() : d;
		if (fresh) target.name = name;
		target.savedBy = p == null ? "console" : p.getGameProfile().name();
		DungeonTemplates.save(server, level, a, b, target).whenComplete((ok, err) -> server.execute(() -> {
			if (err != null) {
				ctx.getSource().sendFailure(Component.literal("Could not save " + name + ": " + err.getMessage()));
				return;
			}
			if (fresh) settings.dungeons.add(target);
			settings.fill();
			saveSettings();
			if (p != null) selections.remove(p.getUUID());
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (Dungeons.Marker m : target.markers) counts.merge(m.type, 1, Integer::sum);
			String markers = counts.isEmpty() ? "no markers" : counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).reduce((x, y) -> x + ", " + y).orElse("");
			ctx.getSource().sendSuccess(() -> Component.literal("Saved " + name + " (" + target.sizeX + " × " + target.sizeY + " × " + target.sizeZ + ", " + target.pieces + " pieces, " + markers
					+ ") in the " + target.pool + " pool." + (target.hasMarker("start") ? "" : " It needs a start marker before players can get it.")
					+ (target.hasMarker("exit") || "boss".equals(target.clearOn) ? "" : " Without an exit marker it can't be cleared.")).withStyle(target.hasMarker("start") ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
		}));
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}
}
