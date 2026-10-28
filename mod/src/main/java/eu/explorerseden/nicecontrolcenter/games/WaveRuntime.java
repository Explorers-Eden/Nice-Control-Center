package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Wave arenas (Games type "waves"): a party of 1–4 starts one from its pad and fights waves of mobs in a box built in
 * the world. Waves are generated from the arena's mob pool (or written by hand per wave), get bigger and tougher,
 * with a boss every few waves. Dead players wait (in the spectator area, or watching a teammate) and come back at the
 * next wave; the party loses when everyone is down at once or the team's lives run out. Rewards after every wave
 * (wave commands, Rewards trigger "waveCleared") and for clearing the game. One game per arena at a time: other
 * parties queue up (in the spectator area) and start when it's free. Exit pads leave early.
 */
public final class WaveRuntime {
	enum State { BREAK, FIGHT, ENDED }

	static final class Game {
		final Arena arena;
		final List<UUID> players = new ArrayList<>();
		final Map<UUID, String> names = new LinkedHashMap<>();
		/** Died this wave: back at the next one. */
		final Set<UUID> down = new HashSet<>();
		/** Left for good (exit pad, /game leave, logout). */
		final Set<UUID> out = new HashSet<>();
		/** Spectating players' game modes (given back when they come back). */
		final Map<UUID, GameType> watching = new HashMap<>();
		final Deque<MobLines.Entry> toSpawn = new ArrayDeque<>();
		final long started = System.currentTimeMillis();
		/** /game try: nobody in it, ends after a few minutes. */
		final boolean trial;
		State state = State.BREAK;
		int wave = 1;
		int cleared;
		int teamDeaths;
		int waveSize;
		long nextAt;
		long waveStarted;
		ServerBossEvent bar;

		Game(Arena arena, boolean trial) {
			this.arena = arena;
			this.trial = trial;
		}

		String tag() {
			return "ncc_wave_" + arena.id;
		}

		boolean in(UUID id) {
			return players.contains(id) && !out.contains(id);
		}
	}

	private static final String SCALED = "ncc_wave_scaled";
	private static final String BOSS = "ncc_wave_boss";
	private static final Identifier TOUGHER = Identifier.fromNamespaceAndPath("nicecontrolcenter", "wave_toughness");
	private static final int PAD_TICKS = 40;
	/** Standing on an exit pad this long (ticks) leaves the game. */
	private static final int EXIT_TICKS = 100;
	private static final double PARTY_RANGE = 8;

	/** arena id → its game. */
	private static final Map<String, Game> games = new HashMap<>();
	/** player → the game they're in (also while down). */
	private static final Map<UUID, Game> playing = new HashMap<>();
	/** arena id → parties waiting (leader first). */
	private static final Map<String, Deque<List<UUID>>> queues = new HashMap<>();
	/** arena id → when the first queued party starts. */
	private static final Map<String, Long> queueStart = new HashMap<>();
	private static final Map<UUID, Integer> onPad = new HashMap<>();
	private static final Map<UUID, Integer> onExit = new HashMap<>();
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	private static final Map<UUID, Long> noGraveUntil = new HashMap<>();
	/** Out of the game while dead: things back and to the pad once they've respawned. */
	private static final Map<UUID, Arena> afterRespawn = new HashMap<>();
	private static final Random random = new Random();

	private WaveRuntime() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (t % 2 == 0) pads(server);
			if (t % 10 == 0) {
				for (Game g : List.copyOf(games.values())) tick(server, g);
				queues(server);
				particles(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Game g : List.copyOf(games.values())) end(server, g, false, null);
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && playing.containsKey(p.getUUID())) died(p.level().getServer(), playing.get(p.getUUID()), p);
			return true;
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
			if (!alive) respawned(now.level().getServer(), now);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.player;
			Game g = playing.get(p.getUUID());
			if (g != null) out(server, g, p, null);
			unqueue(p.getUUID());
			onPad.remove(p.getUUID());
			onExit.remove(p.getUUID());
		});
	}

	// ---------------------------------------------------------------- for others

	/** In a wave game (fighting, or down until the next wave). */
	public static boolean inGame(ServerPlayer p) {
		return playing.containsKey(p.getUUID());
	}

	/** Died in a wave game a moment ago or still in one: no grave. */
	public static boolean noGrave(ServerPlayer p) {
		return inGame(p) || noGraveUntil.getOrDefault(p.getUUID(), 0L) > System.currentTimeMillis();
	}

	/** For the panel: the wave being fought and how many are in it and queued. */
	public static Map<String, Object> live(String arenaId) {
		Map<String, Object> m = new LinkedHashMap<>();
		Game g = games.get(arenaId);
		m.put("playing", g == null ? 0 : g.players.stream().filter(g::in).count());
		m.put("wave", g == null ? 0 : g.wave);
		m.put("queued", queues.getOrDefault(arenaId, new ArrayDeque<>()).size());
		return m;
	}

	/** /game leave: out of the game or the queue; false when they're in neither. */
	public static boolean leave(MinecraftServer server, ServerPlayer p) {
		Game g = playing.get(p.getUUID());
		if (g != null) {
			out(server, g, p, "You left " + g.arena.name + ".");
			return true;
		}
		return unqueue(p.getUUID());
	}

	/** /game try: wave 1 with nobody in it (for testing the arena); it ends after three minutes. */
	public static String trial(MinecraftServer server, Arena a) {
		if (games.containsKey(a.id)) return "A game is running there.";
		String err = check(server, a);
		if (err != null) return err;
		Game g = new Game(a, true);
		g.nextAt = System.currentTimeMillis() + 3000;
		games.put(a.id, g);
		clearBox(server, g);
		NiceControlCenter.LOGGER.info("Games: wave arena {} tried from the console: wave 1 in 3 seconds, ends in 3 minutes", a.name);
		return null;
	}

	/** An arena was removed or changed type: its game ends. */
	public static void stop(MinecraftServer server, Arena a) {
		Game g = games.get(a.id);
		if (g != null) end(server, g, false, "This game was stopped.");
		queues.remove(a.id);
	}

	// ---------------------------------------------------------------- pads and the queue

	private static void pads(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			// Exit pads: five seconds on one and they're out.
			Game in = playing.get(p.getUUID());
			if (in != null) {
				if (onPad(p, in.arena.exitPads) != null) {
					int ticks = onExit.merge(p.getUUID(), 2, Integer::sum);
					if (ticks >= EXIT_TICKS) {
						onExit.remove(p.getUUID());
						out(server, in, p, "You left " + in.arena.name + " early.");
					} else if (ticks % 20 == 2) {
						p.sendOverlayMessage(Component.literal("Leaving " + in.arena.name + " in " + ((EXIT_TICKS - ticks) / 20 + 1) + "… step off to stay").withStyle(ChatFormatting.RED));
					}
				} else {
					onExit.remove(p.getUUID());
				}
				continue;
			}
			Arena a = startPad(p);
			if (a == null || p.isSpectator() || queued(p.getUUID()) || cooldown.getOrDefault(p.getUUID(), 0L) > now) {
				onPad.remove(p.getUUID());
				continue;
			}
			if (!Parties.isLeader(p.getUUID())) {
				if (onPad.merge(p.getUUID(), 2, Integer::sum) % 40 == 2) p.sendOverlayMessage(Component.literal("Your party leader starts " + a.name + " from the pad").withStyle(ChatFormatting.GRAY));
				continue;
			}
			List<ServerPlayer> party = partyNear(server, p, a);
			if (party.size() < a.minParty || party.size() > a.maxParty) {
				if (onPad.merge(p.getUUID(), 2, Integer::sum) % 40 == 2) {
					p.sendOverlayMessage(Component.literal(a.name + " is for " + (a.minParty == a.maxParty ? a.minParty : a.minParty + "–" + a.maxParty) + " player" + (a.maxParty == 1 ? "" : "s")
							+ " (your party here: " + party.size() + ")").withStyle(ChatFormatting.RED));
				}
				continue;
			}
			int ticks = onPad.merge(p.getUUID(), 2, Integer::sum);
			if (ticks < PAD_TICKS) {
				if (ticks % 20 == 2) {
					Game busy = games.get(a.id);
					String who = party.size() == 1 ? "" : " with " + String.join(", ", party.subList(1, party.size()).stream().map(x -> x.getGameProfile().name()).toList());
					String what = busy == null ? a.name + who + " starts" : "Queueing for " + a.name + who;
					p.sendOverlayMessage(Component.literal(what + " in " + ((PAD_TICKS - ticks) / 20 + 1) + "… step off to cancel").withStyle(ChatFormatting.GOLD));
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
				}
				continue;
			}
			onPad.remove(p.getUUID());
			cooldown.put(p.getUUID(), now + 5000);
			if (games.containsKey(a.id) || !queues.getOrDefault(a.id, new ArrayDeque<>()).isEmpty()) {
				queue(server, a, party);
				continue;
			}
			String err = start(server, a, party);
			if (err != null) p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
		}
	}

	private static Arena startPad(ServerPlayer p) {
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || !a.type.equals("waves") || a.start == null) continue;
			if (onPad(p, List.of(a.start)) != null) return a;
		}
		return null;
	}

	private static Games.Pos onPad(ServerPlayer p, List<Games.Pos> pads) {
		String dim = dim(p);
		for (Games.Pos pad : pads) {
			if (pad == null || !pad.dim.equals(dim)) continue;
			double dx = p.getX() - pad.x, dz = p.getZ() - pad.z, dy = p.getY() - pad.y;
			if (dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5) return pad;
		}
		return null;
	}

	/** The leader and the party members close to the pad who can come along. */
	private static List<ServerPlayer> partyNear(MinecraftServer server, ServerPlayer leader, Arena a) {
		List<ServerPlayer> out = new ArrayList<>();
		out.add(leader);
		long now = System.currentTimeMillis();
		for (UUID id : Parties.members(leader.getUUID())) {
			if (id.equals(leader.getUUID())) continue;
			ServerPlayer m = server.getPlayerList().getPlayer(id);
			if (m == null || m.level() != leader.level() || playing.containsKey(id) || queued(id) || m.isSpectator() || cooldown.getOrDefault(id, 0L) > now) continue;
			if (m.distanceToSqr(a.start.x, a.start.y, a.start.z) <= PARTY_RANGE * PARTY_RANGE) out.add(m);
		}
		return out;
	}

	private static void queue(MinecraftServer server, Arena a, List<ServerPlayer> party) {
		Deque<List<UUID>> q = queues.computeIfAbsent(a.id, k -> new ArrayDeque<>());
		q.add(party.stream().map(ServerPlayer::getUUID).toList());
		ServerLevel level = a.spectate == null ? null : GameRuntime.levelOf(server, a.spectate.dim);
		for (ServerPlayer m : party) {
			if (level != null) m.teleportTo(level, a.spectate.x, a.spectate.y, a.spectate.z, Set.of(), a.spectate.yaw, 0, true);
			m.sendSystemMessage(Component.literal("A game is running in " + a.name + ": you're number " + q.size() + " in the queue"
					+ (level != null ? ". Wait here (leaving the area leaves the queue)." : ". Stay near the pad.") + " /game leave leaves it.").withStyle(ChatFormatting.GOLD));
		}
	}

	private static boolean queued(UUID id) {
		for (Deque<List<UUID>> q : queues.values()) for (List<UUID> party : q) if (party.contains(id)) return true;
		return false;
	}

	/** Out of the queue: the whole party if it's their leader, else just them. */
	private static boolean unqueue(UUID id) {
		boolean found = false;
		for (Deque<List<UUID>> q : queues.values()) {
			for (List<UUID> party : List.copyOf(q)) {
				if (!party.contains(id)) continue;
				found = true;
				q.remove(party);
				if (!party.get(0).equals(id)) {
					List<UUID> rest = new ArrayList<>(party);
					rest.remove(id);
					if (!rest.isEmpty()) q.add(rest);
				}
			}
		}
		return found;
	}

	/** Queued parties wait close by; the first one starts a few seconds after the arena is free. */
	private static void queues(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Deque<List<UUID>>> e : queues.entrySet()) {
			Arena a = byId(e.getKey());
			Deque<List<UUID>> q = e.getValue();
			if (a == null || !a.enabled) {
				q.clear();
				continue;
			}
			// Leaders who went away (or logged out) take their party out of the queue.
			for (List<UUID> party : List.copyOf(q)) {
				ServerPlayer leader = server.getPlayerList().getPlayer(party.get(0));
				if (leader == null || !waiting(a, leader)) {
					q.remove(party);
					if (leader != null) leader.sendSystemMessage(Component.literal("You left the queue for " + a.name + ".").withStyle(ChatFormatting.GRAY));
				}
			}
			Game g = games.get(a.id);
			int i = 0;
			for (List<UUID> party : q) {
				i++;
				for (UUID id : party) {
					ServerPlayer m = server.getPlayerList().getPlayer(id);
					if (m == null) continue;
					String state = g != null ? "wave " + g.wave + (a.rounds > 0 ? "/" + a.rounds : "") + " running" : i == 1 ? "starting soon" : "waiting";
					if (server.getTickCount() % 40 == 0) m.sendOverlayMessage(Component.literal(a.name + " · " + state + " · you're " + ordinal(i) + " in the queue").withStyle(ChatFormatting.GOLD));
				}
			}
			if (g != null || q.isEmpty()) {
				queueStart.remove(a.id);
				continue;
			}
			long at = queueStart.computeIfAbsent(a.id, k -> now + 10_000);
			if (now < at) {
				List<UUID> first = q.peek();
				if (server.getTickCount() % 20 == 0) {
					for (UUID id : first) {
						ServerPlayer m = server.getPlayerList().getPlayer(id);
						if (m != null) m.sendOverlayMessage(Component.literal(a.name + " starts in " + ((at - now) / 1000 + 1) + "…").withStyle(ChatFormatting.GREEN));
					}
				}
				continue;
			}
			queueStart.remove(a.id);
			List<UUID> first = q.poll();
			List<ServerPlayer> party = new ArrayList<>();
			for (UUID id : first) {
				ServerPlayer m = server.getPlayerList().getPlayer(id);
				if (m != null && !playing.containsKey(id) && waiting(a, m)) party.add(m);
			}
			if (party.size() < a.minParty || party.size() > a.maxParty) {
				for (ServerPlayer m : party) m.sendSystemMessage(Component.literal("Not enough of your party is here for " + a.name + ": left the queue.").withStyle(ChatFormatting.RED));
				continue;
			}
			String err = start(server, a, party);
			if (err != null) for (ServerPlayer m : party) m.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
		}
	}

	/** Still waiting for the arena: in the spectator area, or near the pad when there's none. */
	private static boolean waiting(Arena a, ServerPlayer p) {
		Games.Pos at = a.spectate != null ? a.spectate : a.start;
		double r = a.spectate != null ? a.spectateRadius + 4 : 24;
		return at != null && dim(p).equals(at.dim) && p.distanceToSqr(at.x, at.y, at.z) <= r * r;
	}

	// ---------------------------------------------------------------- start

	/** Why an arena can't run yet (null when it can). */
	private static String check(MinecraftServer server, Arena a) {
		if (a.spawn == null) return a.name + " has no player spawn yet (/game edit " + a.name + " spawn).";
		if (a.areaMin == null || a.areaMax == null) return a.name + " has no fight box yet (/game edit " + a.name + " corner1 / corner2).";
		if (a.mobSpawns.isEmpty()) return a.name + " has no mob spawns yet (/game edit " + a.name + " mobspawn add).";
		if (a.pool.isEmpty() && a.waveLines.isEmpty()) return a.name + " has no mobs to send (mob pool or wave lines in the panel).";
		if (GameRuntime.levelOf(server, a.spawn.dim) == null) return "That arena's dimension isn't there.";
		return null;
	}

	static String start(MinecraftServer server, Arena a, List<ServerPlayer> party) {
		if (!GameRuntime.settings().enabled) return "Games are closed right now.";
		if (games.containsKey(a.id)) return "A game is running in " + a.name + ".";
		String err = check(server, a);
		if (err != null) return err;
		ServerLevel level = GameRuntime.levelOf(server, a.spawn.dim);
		Game g = new Game(a, false);
		games.put(a.id, g);
		clearBox(server, g);
		for (ServerPlayer p : party) {
			g.players.add(p.getUUID());
			g.names.put(p.getUUID(), p.getGameProfile().name());
			playing.put(p.getUUID(), g);
			unqueue(p.getUUID());
			if (kit(a)) {
				GameClaims.stashFor(p, "waves");
				GameClaims.giveKit(p, a.kit, "waves");
			}
			p.teleportTo(level, a.spawn.x, a.spawn.y, a.spawn.z, Set.of(), a.spawn.yaw, 0, true);
			p.resetFallDistance();
			GameClaims.gameMode(p, false);
			String hint = (a.rounds > 0 ? "Survive " + a.rounds + " waves" : "Survive as many waves as you can")
					+ (a.teamLives > 0 ? "; the team has " + a.teamLives + (a.teamLives == 1 ? " life" : " lives") : "") + ". Exit pads or /game leave take you out.";
			p.sendSystemMessage(Component.literal(a.startMessage.isBlank() ? "▶ " + a.name + ": " + hint : fill(a.startMessage, a, p, "")).withStyle(ChatFormatting.GREEN));
			commands(server, a.startCommands, a, p, "");
		}
		g.nextAt = System.currentTimeMillis() + a.breakSeconds * 1000L;
		level.playSound(null, a.spawn.x, a.spawn.y, a.spawn.z, SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 1f, 1f);
		NiceControlCenter.LOGGER.info("Games: wave arena {} started by {}", a.name, String.join(", ", g.names.values()));
		return null;
	}

	private static boolean kit(Arena a) {
		return "kit".equals(a.gear);
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server, Game g) {
		ServerLevel level = GameRuntime.levelOf(server, g.arena.spawn.dim);
		if (level == null) {
			end(server, g, false, "The arena's dimension is gone.");
			return;
		}
		long now = System.currentTimeMillis();
		if (g.trial && now - g.started > 180_000) {
			end(server, g, false, null);
			return;
		}
		List<ServerPlayer> alive = alive(server, g);
		if (!g.trial) {
			if (alive.isEmpty()) {
				end(server, g, false, "Everyone is down.");
				return;
			}
			if (g.arena.teamLives > 0 && g.teamDeaths > g.arena.teamLives) {
				end(server, g, false, "The team's lives ran out.");
				return;
			}
		}
		AABB box = box(g.arena);
		// Players stay in the box; the dead stay in the spectator area (or keep watching).
		for (ServerPlayer p : alive) {
			if (!dim(p).equals(g.arena.spawn.dim)) {
				out(server, g, p, "You left the arena.");
				continue;
			}
			if (!box.inflate(1).contains(p.position())) {
				p.teleportTo(level, g.arena.spawn.x, g.arena.spawn.y, g.arena.spawn.z, Set.of(), g.arena.spawn.yaw, 0, true);
				p.sendOverlayMessage(Component.literal("Stay in the arena (exit pads or /game leave take you out)").withStyle(ChatFormatting.RED));
			}
		}
		for (UUID id : g.down) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !p.isAlive() || g.watching.containsKey(id) || g.arena.spectate == null) continue;
			Games.Pos s = g.arena.spectate;
			if (!dim(p).equals(s.dim) || p.distanceToSqr(s.x, s.y, s.z) > (g.arena.spectateRadius + 1.0) * (g.arena.spectateRadius + 1.0)) {
				ServerLevel sl = GameRuntime.levelOf(server, s.dim);
				if (sl != null) p.teleportTo(sl, s.x, s.y, s.z, Set.of(), s.yaw, 0, true);
			}
		}
		Spectating.follow(server, g.watching.keySet(), alive);
		if (g.state == State.BREAK) {
			long left = g.nextAt - now;
			bar(server, g, Component.literal(g.arena.name + " · wave " + g.wave + (g.arena.rounds > 0 ? "/" + g.arena.rounds : "") + " in " + (left / 1000 + 1) + " s"), 1f, BossEvent.BossBarColor.YELLOW);
			if (left <= 0) beginWave(server, g, level);
			return;
		}
		if (g.state != State.FIGHT) return;
		List<LivingEntity> mobs = mobs(level, g);
		for (LivingEntity m : mobs) {
			if (!m.entityTags().contains(SCALED)) toughen(g, m);
		}
		// A few at a time, up to the cap of mobs alive at once.
		int room = g.arena.maxAlive - mobs.size();
		int spawned = 0;
		for (int i = 0; i < Math.min(4, room) && !g.toSpawn.isEmpty(); i++) {
			spawned++;
			MobLines.Entry e = g.toSpawn.poll();
			Games.Pos at = g.arena.mobSpawns.get(random.nextInt(g.arena.mobSpawns.size()));
			boolean boss = e.count() < 0;
			MobLines.Entry one = new MobLines.Entry(e.id(), 1, e.nbt());
			MobLines.summon(server, at.dim, at.x + (random.nextDouble() - 0.5), at.y, at.z + (random.nextDouble() - 0.5), one,
					"Tags:[\"" + g.tag() + "\"" + (boss ? ",\"" + BOSS + "\"" : "") + "]");
		}
		if (server.getTickCount() % 20 == 0) {
			for (LivingEntity m : mobs) {
				// Back into the box; after a minute the last few glow.
				if (!box.inflate(2).contains(m.position())) {
					Games.Pos at = g.arena.mobSpawns.get(random.nextInt(g.arena.mobSpawns.size()));
					m.teleportTo(at.x, at.y, at.z);
				}
				if (m instanceof Mob mob && !alive.isEmpty() && (mob.getTarget() == null || !(mob.getTarget() instanceof ServerPlayer t && alive.contains(t)))) {
					ServerPlayer nearest = null;
					for (ServerPlayer p : alive) if (nearest == null || p.distanceToSqr(m) < nearest.distanceToSqr(m)) nearest = p;
					mob.setTarget(nearest);
				}
				if (now - g.waveStarted > 60_000 && mobs.size() <= 3 && g.toSpawn.isEmpty()) m.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
			}
		}
		// (Just summoned ones count too: they're not in the list taken above.)
		int left = mobs.size() + spawned + g.toSpawn.size();
		bar(server, g, Component.literal(g.arena.name + " · wave " + g.wave + (g.arena.rounds > 0 ? "/" + g.arena.rounds : "") + " · " + left + (left == 1 ? " mob left" : " mobs left")),
				g.waveSize == 0 ? 0 : Math.min(1f, left / (float) g.waveSize), BossEvent.BossBarColor.RED);
		if (left == 0) waveCleared(server, g, level);
	}

	/** Players in the game who are up (not down, not out, online). */
	private static List<ServerPlayer> alive(MinecraftServer server, Game g) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : g.players) {
			if (!g.in(id) || g.down.contains(id)) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && p.isAlive()) out.add(p);
		}
		return out;
	}

	private static List<LivingEntity> mobs(ServerLevel level, Game g) {
		return level.getEntitiesOfClass(LivingEntity.class, box(g.arena).inflate(48), e -> e.isAlive() && e.entityTags().contains(g.tag()));
	}

	/** The fight box: both corners' blocks included, their height too. */
	private static AABB box(Arena a) {
		return new AABB(Math.min(a.areaMin.x, a.areaMax.x), Math.min(a.areaMin.y, a.areaMax.y), Math.min(a.areaMin.z, a.areaMax.z),
				Math.max(a.areaMin.x, a.areaMax.x) + 1, Math.max(a.areaMin.y, a.areaMax.y) + 1, Math.max(a.areaMin.z, a.areaMax.z) + 1);
	}

	/** Each wave after the first: more health and damage. */
	private static void toughen(Game g, LivingEntity m) {
		m.addTag(SCALED);
		double more = g.arena.toughness / 100.0 * (g.wave - 1);
		if (more <= 0) return;
		AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.addOrReplacePermanentModifier(new AttributeModifier(TOUGHER, more, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			m.setHealth(m.getMaxHealth());
		}
		AttributeInstance dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
		if (dmg != null) dmg.addOrReplacePermanentModifier(new AttributeModifier(TOUGHER, more, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
	}

	private static void beginWave(MinecraftServer server, Game g, ServerLevel level) {
		Arena a = g.arena;
		g.toSpawn.clear();
		String written = a.waveLines.get(String.valueOf(g.wave));
		if (written != null) {
			for (MobLines.Entry e : MobLines.parseList(written)) for (int i = 0; i < e.count(); i++) g.toSpawn.add(e);
		} else if (!a.pool.isEmpty()) {
			int size = Math.max(1, (int) g.players.stream().filter(g::in).count());
			int want = (int) Math.round((a.firstWave + a.perWave * (g.wave - 1)) * (1 + a.perPlayer / 100.0 * (size - 1)));
			List<MobLines.Entry> pool = a.pool.stream().map(MobLines::parse).filter(e -> e != null).toList();
			while (g.toSpawn.size() < want && !pool.isEmpty()) {
				MobLines.Entry e = pool.get(random.nextInt(pool.size()));
				for (int i = 0; i < e.count() && g.toSpawn.size() < want; i++) g.toSpawn.add(e);
			}
			// The boss comes last; a negative count marks it.
			MobLines.Entry boss = a.bossEvery > 0 && g.wave % a.bossEvery == 0 ? MobLines.parse(a.boss) : null;
			if (boss != null) g.toSpawn.add(new MobLines.Entry(boss.id(), -1, boss.nbt()));
		}
		if (g.toSpawn.isEmpty()) g.toSpawn.add(new MobLines.Entry("minecraft:zombie", 1, ""));
		g.waveSize = g.toSpawn.size();
		g.state = State.FIGHT;
		g.waveStarted = System.currentTimeMillis();
		for (UUID id : g.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !g.in(id)) continue;
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Wave " + g.wave).withStyle(ChatFormatting.RED)));
			p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(g.waveSize + (g.waveSize == 1 ? " mob" : " mobs")).withStyle(ChatFormatting.GRAY)));
		}
		level.playSound(null, a.spawn.x, a.spawn.y, a.spawn.z, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2f, 1f);
	}

	private static void waveCleared(MinecraftServer server, Game g, ServerLevel level) {
		Arena a = g.arena;
		g.cleared = g.wave;
		String wave = String.valueOf(g.wave);
		for (UUID id : g.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !g.in(id)) continue;
			p.sendSystemMessage(Component.literal("✔ Wave " + g.wave + " cleared!").withStyle(ChatFormatting.GREEN));
			for (String line : a.waveCommands) {
				String cmd = fill(line.strip().replaceFirst("^/", ""), a, p, wave).replace("{wave}", wave);
				if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
			}
			RewardsControl.fireGame(server, "waveCleared", p, a.name, g.wave, false);
		}
		level.playSound(null, a.spawn.x, a.spawn.y, a.spawn.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
		if (a.rounds > 0 && g.wave >= a.rounds) {
			end(server, g, true, null);
			return;
		}
		// The dead come back for the next wave.
		for (UUID id : List.copyOf(g.down)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && p.isAlive()) revive(server, g, p);
		}
		g.wave++;
		g.state = State.BREAK;
		g.nextAt = System.currentTimeMillis() + a.breakSeconds * 1000L;
	}

	private static void revive(MinecraftServer server, Game g, ServerPlayer p) {
		g.down.remove(p.getUUID());
		GameType mode = g.watching.remove(p.getUUID());
		if (mode != null) {
			p.setCamera(p);
			p.setGameMode(mode);
		}
		ServerLevel level = GameRuntime.levelOf(server, g.arena.spawn.dim);
		if (level != null) p.teleportTo(level, g.arena.spawn.x, g.arena.spawn.y, g.arena.spawn.z, Set.of(), g.arena.spawn.yaw, 0, true);
		p.resetFallDistance();
		if (kit(g.arena)) GameClaims.giveBackKept(p);
		else GameClaims.restoreFor(p, false);
		p.sendSystemMessage(Component.literal("You're back in!").withStyle(ChatFormatting.GREEN));
	}

	// ---------------------------------------------------------------- deaths and leaving

	private static void died(MinecraftServer server, Game g, ServerPlayer p) {
		noGraveUntil.put(p.getUUID(), System.currentTimeMillis() + 60_000);
		// What they carry is held (kit) or put aside (own gear): nothing drops in the arena, it comes back with them.
		if (kit(g.arena)) GameClaims.clearCarried(p, true);
		else GameClaims.stashFor(p, "waves-death");
		if (g.down.contains(p.getUUID()) || !g.in(p.getUUID())) return;
		g.down.add(p.getUUID());
		g.teamDeaths++;
		String lives = g.arena.teamLives > 0 ? " (" + Math.max(0, g.arena.teamLives - g.teamDeaths) + " team " + (g.arena.teamLives - g.teamDeaths == 1 ? "life" : "lives") + " left)" : "";
		tell(server, g, Component.literal(p.getGameProfile().name() + " is down: back at the next wave" + lives + ".").withStyle(ChatFormatting.RED));
	}

	private static void respawned(MinecraftServer server, ServerPlayer p) {
		Arena gone = afterRespawn.remove(p.getUUID());
		if (gone != null) {
			finishFor(server, gone, p);
			return;
		}
		Game g = playing.get(p.getUUID());
		if (g == null || !g.down.contains(p.getUUID())) return;
		if (g.arena.spectate != null) {
			ServerLevel level = GameRuntime.levelOf(server, g.arena.spectate.dim);
			if (level != null) {
				p.teleportTo(level, g.arena.spectate.x, g.arena.spectate.y, g.arena.spectate.z, Set.of(), g.arena.spectate.yaw, 0, true);
				p.sendSystemMessage(Component.literal("Wait here for the next wave (an exit pad takes you out).").withStyle(ChatFormatting.GRAY));
				return;
			}
		}
		ServerLevel level = GameRuntime.levelOf(server, g.arena.spawn.dim);
		if (level != null) p.teleportTo(level, g.arena.spawn.x, g.arena.spawn.y, g.arena.spawn.z, Set.of(), g.arena.spawn.yaw, 0, true);
		g.watching.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
		p.setGameMode(GameType.SPECTATOR);
		p.sendSystemMessage(Component.literal("You watch your party until the next wave.").withStyle(ChatFormatting.GRAY));
	}

	/** A player is out for good (exit pad, /game leave, logout, another dimension); the game goes on for the others. */
	private static void out(MinecraftServer server, Game g, ServerPlayer p, String why) {
		g.out.add(p.getUUID());
		g.down.remove(p.getUUID());
		playing.remove(p.getUUID());
		if (g.bar != null) g.bar.removePlayer(p);
		GameType mode = g.watching.remove(p.getUUID());
		if (mode != null) {
			p.setCamera(p);
			p.setGameMode(mode);
		}
		if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
		if (p.isAlive()) finishFor(server, g.arena, p);
		else afterRespawn.put(p.getUUID(), g.arena);
		if (!g.trial && g.players.stream().noneMatch(g::in)) end(server, g, false, null);
	}

	/** Out of the game: their things back, and to the start pad. */
	private static void finishFor(MinecraftServer server, Arena a, ServerPlayer p) {
		if (kit(a)) {
			GameClaims.giveBackKept(p);
			GameClaims.restoreFor(p, a.removePickups);
		} else {
			GameClaims.restoreFor(p, false);
		}
		ServerLevel level = a.start == null ? null : GameRuntime.levelOf(server, a.start.dim);
		if (level != null) p.teleportTo(level, a.start.x, a.start.y, a.start.z, Set.of(), a.start.yaw, 0, true);
		p.resetFallDistance();
		cooldown.put(p.getUUID(), System.currentTimeMillis() + 5000);
		GameClaims.gameMode(p, false);
	}

	/** The game is over: won (the last wave cleared) or not; scores, rewards, everyone out, the box cleaned. */
	private static void end(MinecraftServer server, Game g, boolean won, String why) {
		if (g.state == State.ENDED) return;
		g.state = State.ENDED;
		games.remove(g.arena.id);
		Arena a = g.arena;
		clearBox(server, g);
		if (g.bar != null) g.bar.removeAllPlayers();
		// Endless games end by losing: that's their finish.
		boolean finished = won || a.rounds == 0;
		String score = String.valueOf(g.cleared);
		for (UUID id : List.copyOf(g.players)) {
			if (!g.in(id)) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			playing.remove(id);
			if (p == null) continue;
			boolean record = g.cleared > 0 && GameRuntime.recordScore(a, p, g.cleared);
			String line;
			if (won) line = a.finishMessage.isBlank() ? "■ " + a.name + " cleared: all " + g.cleared + " waves!" : fill(a.finishMessage, a, p, score);
			else if (a.rounds == 0 && !a.finishMessage.isBlank()) line = fill(a.finishMessage, a, p, score);
			else line = a.loseMessage.isBlank() ? "✖ " + a.name + ": " + (why == null ? "the game is over" : why.replaceAll("\\.$", "")) + " after " + g.cleared + (g.cleared == 1 ? " wave" : " waves") + "."
					: fill(a.loseMessage, a, p, score).replace("{wave}", score);
			p.sendSystemMessage(Component.literal(line + (record ? " · new personal best!" : "")).withStyle(won ? ChatFormatting.GOLD : record ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
			if (finished && g.cleared > 0) {
				commands(server, a.finishCommands, a, p, score);
				RewardsControl.fireGame(server, "gameFinished", p, a.name, g.cleared, false);
				if (record) RewardsControl.fireGame(server, "gameRecord", p, a.name, g.cleared, false);
			}
			GameType mode = g.watching.remove(id);
			if (mode != null) {
				p.setCamera(p);
				p.setGameMode(mode);
			}
			if (p.isAlive()) finishFor(server, a, p);
			else afterRespawn.put(id, a);
		}
		NiceControlCenter.LOGGER.info("Games: wave arena {} ended ({}) after {} waves: {}", a.name, won ? "cleared" : why == null ? "over" : why, g.cleared, String.join(", ", g.names.values()));
	}

	/** Mobs of the game (and anything left lying in the box) gone. */
	private static void clearBox(MinecraftServer server, Game g) {
		ServerLevel level = GameRuntime.levelOf(server, g.arena.spawn.dim);
		if (level == null || g.arena.areaMin == null || g.arena.areaMax == null) return;
		AABB box = box(g.arena);
		for (Entity e : level.getEntitiesOfClass(Entity.class, box.inflate(48), e -> e.entityTags().contains(g.tag()))) e.discard();
		ConsoleRunner.quiet(server, "kill @e[tag=" + g.tag() + "]");
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) e.discard();
	}

	// ---------------------------------------------------------------- helpers

	private static void bar(MinecraftServer server, Game g, Component text, float progress, BossEvent.BossBarColor color) {
		if (g.bar == null) g.bar = new ServerBossEvent(UUID.randomUUID(), text, color, BossEvent.BossBarOverlay.NOTCHED_10);
		g.bar.setName(text);
		g.bar.setColor(color);
		g.bar.setProgress(Math.max(0f, Math.min(1f, progress)));
		for (UUID id : g.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && g.in(id)) g.bar.addPlayer(p);
		}
	}

	private static void tell(MinecraftServer server, Game g, Component text) {
		for (UUID id : g.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && g.in(id)) p.sendSystemMessage(text);
		}
	}

	/** Start pads gold, exit pads red, when someone's close. */
	private static void particles(MinecraftServer server) {
		double turn = server.getTickCount() / 20.0 * 0.4;
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || !a.type.equals("waves")) continue;
			if (a.start != null) ring(server, a.start, new DustParticleOptions(0xFFC83D, 1.2f), turn);
			for (Games.Pos pad : a.exitPads) ring(server, pad, new DustParticleOptions(0xFF4A4A, 1.2f), turn);
		}
	}

	private static void ring(MinecraftServer server, Games.Pos pad, DustParticleOptions dust, double turn) {
		ServerLevel level = GameRuntime.levelOf(server, pad.dim);
		if (level == null || level.getNearestPlayer(pad.x, pad.y, pad.z, 24, false) == null) return;
		for (int i = 0; i < 8; i++) {
			double ang = i * Math.PI / 4 + turn;
			level.sendParticles(dust, pad.x + Math.cos(ang) * 0.8, pad.y + 0.15, pad.z + Math.sin(ang) * 0.8, 1, 0, 0, 0, 0);
		}
	}

	private static String fill(String text, Arena a, ServerPlayer p, String score) {
		return text.replace("{player}", p.getGameProfile().name()).replace("{game}", a.name).replace("{score}", score);
	}

	private static void commands(MinecraftServer server, List<String> lines, Arena a, ServerPlayer p, String score) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), a, p, score);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
		}
	}

	private static Arena byId(String id) {
		for (Arena a : GameRuntime.settings().arenas) if (a.id.equals(id)) return a;
		return null;
	}

	private static String dim(ServerPlayer p) {
		return p.level().dimension().identifier().toString();
	}

	private static String ordinal(int n) {
		return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
	}
}
