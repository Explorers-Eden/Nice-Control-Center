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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Dungeons.Dungeon;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.core.AsyncFiles;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;

/**
 * Runs the dungeons: a player standing on a pad gets a random dungeon from the pad's pool, copied into a free slot
 * of the dungeon dimension (slots sit slotSpacing blocks apart along +X). The copy is pasted a few pieces per tick
 * while a boss bar shows how far it is, then the player is brought to its start marker. Reaching an exit marker
 * clears it; dying, leaving the box or the time running out ends it. Afterwards the slot is wiped (also on start-up,
 * so no copy outlives a restart) and freed.
 */
public final class DungeonRuntime {
	public static final String PERMISSION = GameRuntime.PERMISSION;
	/** Where copies stand (their lowest corner's Y). */
	private static final int BASE_Y = 64;
	/** Ticks a player stands on a pad before the run starts. */
	private static final int PAD_TICKS = 40;

	enum State { LOADING, PASTING, RUNNING, ENDING, WIPING }

	/** One copy of a dungeon and the players in it. */
	static final class Run {
		final int slot;
		final Dungeon dungeon;
		final Set<UUID> players = new LinkedHashSet<>();
		final Set<UUID> out = new LinkedHashSet<>();
		final Map<UUID, String> names = new HashMap<>();
		State state = State.LOADING;
		CompletableFuture<List<DungeonTemplates.Piece>> loading;
		ArrayDeque<DungeonTemplates.Piece> toPaste;
		int total;
		/** Wiping: the pieces still to clear (dx, dz). */
		ArrayDeque<int[]> toWipe;
		BlockPos min;
		BlockPos max;
		BlockPos start;
		final List<BlockPos> exits = new ArrayList<>();
		long startedAt;
		long endAt;
		boolean cleared;
		ServerBossEvent bar;

		Run(int slot, Dungeon d) {
			this.slot = slot;
			this.dungeon = d;
		}

		long millis() {
			return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
		}
	}

	private static Dungeons.Settings settings = new Dungeons.Settings().fill();
	private static long modified = -1;
	private static final Map<Integer, Run> slots = new LinkedHashMap<>();
	/** player → the run they're in. */
	private static final Map<UUID, Run> playing = new HashMap<>();
	/** player → ticks stood on a pad. */
	private static final Map<UUID, Integer> onPad = new HashMap<>();
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	/** player → the dungeon they played last (so the next pick is another when the pool has more). */
	private static final Map<UUID, String> lastPlayed = new HashMap<>();
	/** Admins making a dungeon: name and the two corners. */
	private static final Map<UUID, Selection> selections = new HashMap<>();
	private static Path slotsFile;
	private static final Random random = new Random();

	private record Selection(String name, String dim, BlockPos a, BlockPos b, long until) {
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
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			slotsFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-dungeon-slots.json");
			wipeLeftovers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Run r : List.copyOf(slots.values())) {
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null) sendBack(server, p);
				}
				if (r.bar != null) r.bar.removeAllPlayers();
			}
			// What's in use stays in the slots file and is wiped on the next start.
		});
		ServerTickEvents.END_SERVER_TICK.register(DungeonRuntime::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p && playing.containsKey(p.getUUID())) {
				Run r = playing.get(p.getUUID());
				out(p.level().getServer(), r, p, "You died: you're out of this run.", false);
			}
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

	static boolean inDungeonDimension(ServerPlayer p) {
		return p.level().dimension().identifier().toString().equals(Dungeons.DIMENSION);
	}

	static ServerLevel level(MinecraftServer server) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(Dungeons.DIMENSION)));
	}

	/** For the panel: the runs going on. */
	public static List<Map<String, Object>> view() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Run r : slots.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("slot", r.slot);
			m.put("dungeon", r.dungeon.name);
			m.put("state", r.state.name().toLowerCase(Locale.ROOT));
			m.put("players", r.players.stream().map(id -> r.names.getOrDefault(id, "?")).toList());
			m.put("millis", r.millis());
			out.add(m);
		}
		return out;
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
			case RUNNING -> {
				if (server.getTickCount() % 5 != 0) return;
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p == null || r.out.contains(id)) continue;
					if (p.level() != level || !inBox(r, p)) {
						out(server, r, p, "You left the dungeon: you're out of this run.", false);
						continue;
					}
					if ("exit".equals(r.dungeon.clearOn)) {
						for (BlockPos e : r.exits) {
							if (p.distanceToSqr(e.getX() + 0.5, e.getY() + 0.5, e.getZ() + 0.5) < 2.25) {
								clear(server, r);
								return;
							}
						}
					}
					if (server.getTickCount() % 10 == 0) hud(p, r);
				}
				if (r.dungeon.timeLimit > 0 && r.millis() > r.dungeon.timeLimit * 60_000L) {
					fail(server, r, "Time's up.");
					return;
				}
				if (r.players.stream().allMatch(r.out::contains)) fail(server, r, null);
			}
			case ENDING -> {
				if (System.currentTimeMillis() < r.endAt) return;
				for (UUID id : List.copyOf(r.players)) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && (p.level() == level || inDungeonDimension(p))) sendBack(server, p);
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

	private static boolean inBox(Run r, ServerPlayer p) {
		return p.getX() >= r.min.getX() - 3 && p.getX() <= r.max.getX() + 4 && p.getZ() >= r.min.getZ() - 3 && p.getZ() <= r.max.getZ() + 4
				&& p.getY() >= r.min.getY() - 24 && p.getY() <= r.max.getY() + 24;
	}

	private static void hud(ServerPlayer p, Run r) {
		long left = r.players.size() - r.out.size();
		String limit = r.dungeon.timeLimit > 0 ? " / " + GameRuntime.clock(r.dungeon.timeLimit * 60_000L) : "";
		p.sendOverlayMessage(Component.literal(r.dungeon.name + " · " + GameRuntime.clock(r.millis()) + limit + (r.players.size() > 1 ? " · " + left + " in" : ""))
				.withStyle(ChatFormatting.LIGHT_PURPLE));
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
			int ticks = onPad.merge(p.getUUID(), 2, Integer::sum);
			if (ticks < PAD_TICKS) {
				if (ticks % 20 == 2) {
					p.sendOverlayMessage(Component.literal("Dungeon (" + pad.pool + ") in " + ((PAD_TICKS - ticks) / 20 + 1) + "… step off to cancel").withStyle(ChatFormatting.LIGHT_PURPLE));
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
				}
				continue;
			}
			onPad.remove(p.getUUID());
			cooldown.put(p.getUUID(), now + 5000);
			String err = start(server, List.of(p), pad.pool);
			if (err != null) p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
		}
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
			playing.put(p.getUUID(), r);
			lastPlayed.put(p.getUUID(), d.id);
			r.bar.addPlayer(p);
		}
		slots.put(slot, r);
		saveSlots();
		return null;
	}

	/** The copy is ready: markers become air, everyone goes to the start. */
	private static void begin(MinecraftServer server, ServerLevel level, Run r) {
		for (Dungeons.Marker m : r.dungeon.markers) {
			BlockPos at = r.min.offset(m.x, m.y, m.z);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
			switch (m.type) {
				case "start" -> {
					if (r.start == null) r.start = at;
				}
				case "exit" -> r.exits.add(at);
				default -> {
					// Checkpoints, mobs and bosses come with the next release.
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
			p.teleportTo(level, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, Set.of(), p.getYRot(), 0, true);
			p.resetFallDistance();
			p.sendSystemMessage(Component.literal(fill(r.dungeon.enterMessage.isEmpty() ? "▶ {dungeon}: find the way out!" : r.dungeon.enterMessage, r, p)).withStyle(ChatFormatting.LIGHT_PURPLE));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(r.dungeon.name).withStyle(ChatFormatting.LIGHT_PURPLE)));
			commands(server, r.dungeon.enterCommands, r, p);
		}
		level.playSound(null, r.start, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1f, 0.8f);
	}

	// ---------------------------------------------------------------- ending

	/** A player is out (died, left, logged out); the run carries on for the others. */
	private static void out(MinecraftServer server, Run r, ServerPlayer p, String why, boolean loggedOut) {
		if (r.state == State.LOADING || r.state == State.PASTING) {
			// Not in yet: they simply don't come along.
			r.players.remove(p.getUUID());
			playing.remove(p.getUUID());
			if (r.bar != null) r.bar.removePlayer(p);
			if (r.players.isEmpty()) startWipe(r);
			return;
		}
		if (r.out.contains(p.getUUID())) return;
		r.out.add(p.getUUID());
		playing.remove(p.getUUID());
		cooldown.put(p.getUUID(), System.currentTimeMillis() + settings.cooldown * 1000L);
		if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
		commands(server, r.dungeon.exitCommands, r, p);
		// After dying the respawn brings them out (to the hub spawn when there is one); else now.
		if (p.isAlive() && (loggedOut || inDungeonDimension(p))) sendBack(server, p);
	}

	private static void clear(MinecraftServer server, Run r) {
		r.cleared = true;
		end(server, r, r.dungeon.clearedMessage.isEmpty() ? "■ {dungeon} cleared in {time}!" : r.dungeon.clearedMessage, ChatFormatting.GOLD);
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
	}

	/** Tells everyone still in, runs the exit commands, and sends them back after a few seconds. */
	private static void end(MinecraftServer server, Run r, String message, ChatFormatting color) {
		r.state = State.ENDING;
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
		if (r.bar != null) r.bar.removeAllPlayers();
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
				.replace("{player}", p == null ? party : p.getGameProfile().name());
	}

	private static void commands(MinecraftServer server, List<String> lines, Run r, ServerPlayer p) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), r, p);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd);
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
					out(ctx.getSource().getServer(), r, p, "You left the dungeon.", true);
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
				.then(Commands.literal("create").requires(admin).then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					String name = StringArgumentType.getString(ctx, "name");
					if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "A one-word name (letters, digits, - and _).");
					selections.put(p.getUUID(), new Selection(name, p.level().dimension().identifier().toString(), p.blockPosition(), null, System.currentTimeMillis() + 30 * 60_000L));
					ctx.getSource().sendSuccess(() -> Component.literal("Corner 1 of " + name + " is here. Go to the opposite corner and use /dungeon corner2, then /dungeon save " + name
							+ ". Inside, place data structure blocks as markers: start (required), exit, checkpoint, mob:<id> [xN], boss:<id>.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
					return 1;
				})))
				.then(Commands.literal("corner2").requires(admin).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Selection s = selections.get(p.getUUID());
					if (s == null) return fail(ctx, "Start with /dungeon create <name> at the first corner.");
					if (!s.dim.equals(p.level().dimension().identifier().toString())) return fail(ctx, "Both corners have to be in the same dimension.");
					BlockPos b = p.blockPosition();
					selections.put(p.getUUID(), new Selection(s.name, s.dim, s.a, b, System.currentTimeMillis() + 30 * 60_000L));
					int sx = Math.abs(s.a.getX() - b.getX()) + 1, sy = Math.abs(s.a.getY() - b.getY()) + 1, sz = Math.abs(s.a.getZ() - b.getZ()) + 1;
					ctx.getSource().sendSuccess(() -> Component.literal("Box: " + sx + " × " + sy + " × " + sz + ". /dungeon save " + s.name + " keeps it.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
					return 1;
				}))
				.then(Commands.literal("save").requires(admin).then(dungeonArg.get().executes(DungeonRuntime::save)
						.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
								.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(DungeonRuntime::save)))))
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
							ctx.getSource().sendSuccess(() -> Component.literal("Pad for the " + pad.pool + " pool here: standing on it for two seconds starts a run."), true);
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
					List<Map<String, Object>> runs = view();
					String text = runs.isEmpty() ? "No dungeon runs right now." : runs.stream().map(m -> "slot " + m.get("slot") + ": " + m.get("dungeon") + " · " + m.get("state") + " · " + m.get("players"))
							.reduce((a, b) -> a + "\n" + b).orElse("");
					ctx.getSource().sendSuccess(() -> Component.literal(text), false);
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
