package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Quick rounds (Games types ghasts, colorfloor, anvils, treasure, fishing): the first player on the start point opens a
 * join window, everyone who steps on it before it closes plays the same round, each for their own score. Ghast deflect
 * (snowballs send fireballs back; a ghast killed by one scores), colour floor (a 3×3 grid of coloured platforms, one
 * colour vanishes at a time, refilled and faster every few colours, mobs now and then), anvil rain (survive the falling
 * anvils), treasure grab (glowing items appear, walk into them) and fishing frenzy (catches score points). Survival
 * rounds (colour floor, anvils) end when the last player is out (with two or more, the last one standing wins); point
 * rounds when the time is up. Players never die here: a deadly hit knocks them out of the round. With a spectator area
 * set, players who step on the pad while a round is on wait there for the next one (it opens its join window for them
 * when this one ends), and players who are out watch the rest of the round from there.
 */
public final class QuickRuntime {
	enum State { LOBBY, PLAYING, ENDED }

	static final class Round {
		final Arena arena;
		final List<UUID> players = new ArrayList<>();
		final Map<UUID, String> names = new LinkedHashMap<>();
		final Map<UUID, Long> score = new HashMap<>();
		/** Out of the round (fell, knocked out, left). */
		final Set<UUID> out = new HashSet<>();
		/** Players whose score counts (out by playing, or there at the end), in the order they went out. */
		final List<UUID> counted = new ArrayList<>();
		/** Waiting in the spectator area for the next round. */
		final List<UUID> next = new ArrayList<>();
		/** Out of this round, watching from the spectator area. */
		final Set<UUID> watching = new HashSet<>();
		State state = State.LOBBY;
		long startsAt;
		long started;
		int startedWith;
		ServerBossEvent bar;
		// Colour floor: each platform's colour (index into arena.colors), which are gone, the called colour.
		final int[] platform = new int[9];
		final boolean[] gone = new boolean[9];
		int called = -1;
		long callAt;
		long nextCallAt;
		long refillAt;
		int vanished;
		int colours;
		double seconds;
		// Anvil rain.
		double due;
		final List<FallingBlockEntity> falling = new ArrayList<>();
		final Map<BlockPos, Long> landed = new HashMap<>();
		// Treasure grab: item entity → points.
		final Map<UUID, Integer> treasure = new HashMap<>();

		Round(Arena arena) {
			this.arena = arena;
		}

		String tag() {
			return "ncc_quick_" + arena.id;
		}

		boolean in(UUID id) {
			return players.contains(id) && !out.contains(id);
		}

		long elapsed() {
			return System.currentTimeMillis() - started;
		}
	}

	private static final int EXIT_TICKS = 100;
	private static final double LOBBY_RANGE = 12;
	/** arena id → its round (waiting for players or playing). */
	private static final Map<String, Round> rounds = new HashMap<>();
	/** Players in a round being played (not out). */
	private static final Map<UUID, Round> playing = new HashMap<>();
	private static final Map<UUID, Integer> onExit = new HashMap<>();
	private static final Map<UUID, Long> cooldown = new HashMap<>();
	private static final Map<UUID, Long> noGraveUntil = new HashMap<>();
	private static final Random random = new Random();

	private QuickRuntime() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (!rounds.isEmpty()) deflect(server);
			if (t % 2 == 0) {
				pads(server);
				for (Round r : List.copyOf(rounds.values())) tick(server, r);
			}
			if (t % 10 == 0) particles(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Round r : List.copyOf(rounds.values())) end(server, r, "The server is stopping.");
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof Ghast) || !(source.getEntity() instanceof ServerPlayer p)) return;
			Round r = playing.get(p.getUUID());
			if (r == null || !r.arena.type.equals("ghasts") || !entity.entityTags().contains(r.tag())) return;
			long n = r.score.merge(p.getUUID(), 1L, Long::sum);
			p.sendOverlayMessage(Component.literal("+1 ghast · " + n).withStyle(ChatFormatting.GOLD));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.6f);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.player;
			Round r = playing.get(p.getUUID());
			if (r != null) out(server, r, p, null, false);
			for (Round lobby : rounds.values()) if (lobby.state == State.LOBBY && lobby.players.remove(p.getUUID())) lobby.names.remove(p.getUUID());
			onExit.remove(p.getUUID());
		});
	}

	// ---------------------------------------------------------------- for others

	/** In a quick round being played (the join window doesn't count). */
	public static boolean inGame(ServerPlayer p) {
		return playing.containsKey(p.getUUID());
	}

	public static boolean noGrave(ServerPlayer p) {
		return inGame(p) || noGraveUntil.getOrDefault(p.getUUID(), 0L) > System.currentTimeMillis();
	}

	/**
	 * From the death hook, before anything else: a deadly hit in a round knocks the player out of it instead
	 * (healed, nothing dropped). True when the death is called off.
	 */
	public static boolean knockOut(ServerPlayer p) {
		Round r = playing.get(p.getUUID());
		if (r == null) return false;
		p.setHealth(p.getMaxHealth());
		p.clearFire();
		p.removeAllEffects();
		p.resetFallDistance();
		noGraveUntil.put(p.getUUID(), System.currentTimeMillis() + 10_000);
		MinecraftServer server = p.level().getServer();
		// Out of the damage handling first: teleporting in the middle of it isn't safe.
		server.execute(() -> {
			if (playing.get(p.getUUID()) == r) out(server, r, p, "You were knocked out.", true);
		});
		return true;
	}

	/** For the panel: players in the round (or waiting to start). */
	public static Map<String, Object> live(String arenaId) {
		Map<String, Object> m = new LinkedHashMap<>();
		Round r = rounds.get(arenaId);
		m.put("playing", r == null ? 0 : r.players.stream().filter(r::in).count());
		m.put("waiting", r != null && r.state == State.LOBBY);
		return m;
	}

	/** /game leave: out of the round or the join window; false when in neither. */
	public static boolean leave(MinecraftServer server, ServerPlayer p) {
		Round r = playing.get(p.getUUID());
		if (r != null) {
			out(server, r, p, "You left " + r.arena.shown() + ".", false);
			return true;
		}
		for (Round on : rounds.values()) {
			if (on.next.remove(p.getUUID())) {
				p.sendSystemMessage(Component.literal("You left the queue for " + on.arena.shown() + ".").withStyle(ChatFormatting.GRAY));
				return true;
			}
		}
		for (Round lobby : rounds.values()) {
			if (lobby.state == State.LOBBY && lobby.players.remove(p.getUUID())) {
				lobby.names.remove(p.getUUID());
				cooldown.put(p.getUUID(), System.currentTimeMillis() + 5000);
				p.sendSystemMessage(Component.literal("You left the queue for " + lobby.arena.shown() + ".").withStyle(ChatFormatting.GRAY));
				return true;
			}
		}
		return false;
	}

	/** /game try: a round with only the admin in it, right away. */
	public static String trial(MinecraftServer server, Arena a, ServerPlayer p) {
		if (rounds.containsKey(a.id)) return "A round is on there.";
		if (playing.containsKey(p.getUUID()) || WaveRuntime.inGame(p) || GameRuntime.inRun(p)) return "You're already in a game: /game leave first.";
		String err = check(server, a);
		if (err != null) return err;
		Round r = new Round(a);
		r.players.add(p.getUUID());
		r.names.put(p.getUUID(), p.getGameProfile().name());
		rounds.put(a.id, r);
		start(server, r);
		return null;
	}

	/** An arena was removed or changed: its round ends. */
	public static void stop(MinecraftServer server, Arena a) {
		Round r = rounds.get(a.id);
		if (r != null) end(server, r, "This game was stopped.");
	}

	// ---------------------------------------------------------------- the start point and the join window

	private static void pads(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Round in = playing.get(p.getUUID());
			if (in != null) {
				exitPad(server, in, p);
				continue;
			}
			Arena a = startPad(p);
			if (a == null || p.isSpectator() || cooldown.getOrDefault(p.getUUID(), 0L) > now || WaveRuntime.inGame(p) || GameRuntime.inRun(p)) continue;
			if (!GameRuntime.settings().enabled) continue;
			Round r = rounds.get(a.id);
			if (r != null && r.players.contains(p.getUUID())) continue;
			if (r != null && r.state != State.LOBBY) {
				if (a.spectate != null && !r.next.contains(p.getUUID()) && r.next.size() < a.maxPlayers && toSpectate(server, a, p)) {
					r.next.add(p.getUUID());
					r.names.putIfAbsent(p.getUUID(), p.getGameProfile().name());
					p.sendSystemMessage(Component.literal("A round of " + a.shown() + " is on: you're in the queue for the next one (" + r.next.size() + "). Wait here; walking away leaves the queue.").withStyle(ChatFormatting.GOLD));
				} else if (server.getTickCount() % 40 == 0) {
					p.sendOverlayMessage(Component.literal("A round of " + a.shown() + " is on; the next one starts after it").withStyle(ChatFormatting.GRAY));
				}
				continue;
			}
			if (rounds.values().stream().anyMatch(o -> o.state == State.LOBBY && o.players.contains(p.getUUID()) || o.next.contains(p.getUUID()))) continue;
			if (r == null) {
				String err = check(server, a);
				if (err != null) {
					if (server.getTickCount() % 40 == 0) p.sendOverlayMessage(Component.literal(a.shown() + " isn't set up yet").withStyle(ChatFormatting.RED));
					continue;
				}
				r = new Round(a);
				r.startsAt = now + a.joinSeconds * 1000L;
				rounds.put(a.id, r);
			}
			if (r.players.size() >= a.maxPlayers) {
				if (server.getTickCount() % 40 == 0) p.sendOverlayMessage(Component.literal(a.shown() + " is full (" + a.maxPlayers + "); the next round starts after it").withStyle(ChatFormatting.RED));
				continue;
			}
			r.players.add(p.getUUID());
			r.names.put(p.getUUID(), p.getGameProfile().name());
			p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
			long left = Math.max(0, (r.startsAt - now) / 1000);
			p.sendSystemMessage(Component.literal("▶ You're in the next round of " + a.shown() + (left > 0 ? ": it starts in " + left + " s. Others can step on the pad to join." : ".")).withStyle(ChatFormatting.GREEN));
		}
	}

	private static Arena startPad(ServerPlayer p) {
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || !Games.quick(a.type) || a.start == null) continue;
			if (onPad(p, a.start)) return a;
		}
		return null;
	}

	private static boolean onPad(ServerPlayer p, Games.Pos pad) {
		if (pad == null || !pad.dim.equals(dim(p))) return false;
		double dx = p.getX() - pad.x, dz = p.getZ() - pad.z, dy = p.getY() - pad.y;
		return dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5;
	}

	private static void exitPad(MinecraftServer server, Round r, ServerPlayer p) {
		boolean on = false;
		for (Games.Pos pad : r.arena.exitPads) on |= onPad(p, pad);
		if (!on) {
			onExit.remove(p.getUUID());
			return;
		}
		int ticks = onExit.merge(p.getUUID(), 2, Integer::sum);
		if (ticks >= EXIT_TICKS) {
			onExit.remove(p.getUUID());
			out(server, r, p, "You left " + r.arena.shown() + " early.", false);
		} else if (ticks % 20 == 2) {
			p.sendOverlayMessage(Component.literal("Leaving " + r.arena.shown() + " in " + ((EXIT_TICKS - ticks) / 20 + 1) + "… step off to stay").withStyle(ChatFormatting.RED));
		}
	}

	// ---------------------------------------------------------------- start

	/** Why an arena can't run yet (null when it can). */
	static String check(MinecraftServer server, Arena a) {
		String n = a.name;
		if (a.type.equals("colorfloor")) {
			if (a.corner == null) return n + " has no grid yet (/game edit " + n + " grid, standing where its corner goes).";
			return GameRuntime.levelOf(server, a.corner.dim) == null ? "That arena's dimension isn't there." : null;
		}
		if (a.spawn == null) return n + " has no player spawn yet (/game edit " + n + " spawn).";
		if (a.areaMin == null || a.areaMax == null) return n + " has no play box yet (/game edit " + n + " corner1 / corner2).";
		if (a.type.equals("ghasts") && a.mobSpawns.isEmpty()) return n + " has no ghast spawns yet (/game edit " + n + " mobspawn add).";
		if (a.type.equals("ghasts") && a.pool.isEmpty()) return n + " has no ghasts to send (mob pool in the panel).";
		if (a.type.equals("treasure") && treasures(a).isEmpty()) return n + " has no treasure items (panel).";
		return GameRuntime.levelOf(server, a.spawn.dim) == null ? "That arena's dimension isn't there." : null;
	}

	private static void start(MinecraftServer server, Round r) {
		Arena a = r.arena;
		ServerLevel level = level(server, a);
		String err = check(server, a);
		List<ServerPlayer> going = new ArrayList<>();
		for (UUID id : List.copyOf(r.players)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			boolean near = p != null && nearPad(a, p);
			// (/game try puts the admin in from anywhere.)
			if (p != null && p.isAlive() && (near || r.players.size() == 1 && r.startsAt == 0)) going.add(p);
			else {
				r.players.remove(id);
				r.names.remove(id);
			}
		}
		if (err != null || level == null || going.isEmpty()) {
			rounds.remove(a.id);
			for (ServerPlayer p : going) p.sendSystemMessage(Component.literal(err != null ? err : "Nobody was left on the pad.").withStyle(ChatFormatting.RED));
			return;
		}
		r.state = State.PLAYING;
		r.started = System.currentTimeMillis();
		r.startedWith = going.size();
		clearBox(server, r);
		if (a.type.equals("colorfloor")) {
			r.seconds = a.roundSeconds;
			buildGrid(level, r);
			r.nextCallAt = r.started + 3000;
		}
		Games.Pos spawn = spawnOf(a);
		for (ServerPlayer p : going) {
			playing.put(p.getUUID(), r);
			r.score.put(p.getUUID(), 0L);
			if (kit(a)) {
				GameClaims.stashFor(p, "quick");
				GameClaims.giveKit(p, a.kit, "quick");
				for (ItemStack st : starter(a)) GameClaims.giveKitItem(p, st, "quick");
			}
			p.teleportTo(level, spawn.x + (going.size() > 1 ? random.nextDouble() * 2 - 1 : 0), spawn.y, spawn.z + (going.size() > 1 ? random.nextDouble() * 2 - 1 : 0), Set.of(), spawn.yaw, 0, true);
			p.resetFallDistance();
			GameClaims.gameMode(p, false);
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(a.shown()).withStyle(st -> st.withColor(a.rgb()))));
			p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(goal(a)).withStyle(ChatFormatting.GRAY)));
			p.sendSystemMessage(Component.literal(a.startMessage.isBlank() ? "▶ " + a.shown() + ": " + goal(a) + (a.exitPads.isEmpty() ? " /game leave stops." : " Exit pads or /game leave stop.") : fill(a.startMessage, a, p, "")).withStyle(ChatFormatting.GREEN));
			commands(server, a.startCommands, a, p, "");
		}
		level.playSound(null, spawn.x, spawn.y, spawn.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1f, 2f);
		NiceControlCenter.LOGGER.info("Games: quick round {} started with {}", a.name, String.join(", ", r.names.values()));
	}

	private static String goal(Arena a) {
		String time = a.timeLimit > 0 ? GameRuntime.clock(a.timeLimit * 1000L).replaceAll("\\.\\d$", "") : "";
		return switch (a.type) {
			case "ghasts" -> "Hit the fireballs back with snowballs: every ghast they kill counts" + (time.isEmpty() ? "." : " (" + time + ").");
			case "colorfloor" -> "The called colour vanishes. Stand on another one!";
			case "anvils" -> "Dodge the falling anvils as long as you can.";
			case "treasure" -> "Grab the glowing treasure" + (time.isEmpty() ? "." : " (" + time + ").");
			default -> "Catch as much as you can" + (time.isEmpty() ? "." : " (" + time + ").");
		};
	}

	/** What a kit round hands out on top of the arena's own kit. */
	private static List<ItemStack> starter(Arena a) {
		return switch (a.type) {
			case "ghasts" -> List.of(new ItemStack(Items.SNOWBALL, 16));
			case "fishing" -> List.of(new ItemStack(Items.FISHING_ROD));
			default -> List.of();
		};
	}

	private static boolean kit(Arena a) {
		return "kit".equals(a.gear);
	}

	// ---------------------------------------------------------------- the loop

	private static void tick(MinecraftServer server, Round r) {
		long now = System.currentTimeMillis();
		Arena a = r.arena;
		if (r.state == State.LOBBY) {
			r.players.removeIf(id -> {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				boolean gone = p == null || !nearPad(a, p);
				if (gone && p != null) p.sendSystemMessage(Component.literal("You walked away from " + a.shown() + ": not in the next round.").withStyle(ChatFormatting.GRAY));
				return gone;
			});
			if (r.players.isEmpty()) {
				rounds.remove(a.id);
				if (r.bar != null) r.bar.removeAllPlayers();
				return;
			}
			long left = r.startsAt - now;
			bar(server, r, Component.literal(a.shown() + " starts in " + (left / 1000 + 1) + " s · " + r.players.size() + "/" + a.maxPlayers + " players · step on the pad to join"),
					a.joinSeconds == 0 ? 1f : left / (a.joinSeconds * 1000f), BossEvent.BossBarColor.YELLOW);
			if (left <= 0) start(server, r);
			return;
		}
		if (r.state != State.PLAYING) return;
		ServerLevel level = level(server, a);
		if (level == null) {
			end(server, r, "The arena's dimension is gone.");
			return;
		}
		// The queue for the next round stays in the spectator area.
		r.next.removeIf(id -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			boolean gone = p == null || a.spectate == null || !inSpectate(a, p, 3);
			if (gone && p != null) p.sendSystemMessage(Component.literal("You left the spectator area: out of the queue for " + a.shown() + ".").withStyle(ChatFormatting.GRAY));
			if (!gone && server.getTickCount() % 40 == 0) p.sendOverlayMessage(Component.literal(a.shown() + ": you're next (" + (r.next.indexOf(id) + 1) + " in the queue)").withStyle(ChatFormatting.GOLD));
			return gone;
		});
		boolean survival = survival(a);
		List<ServerPlayer> alive = alive(server, r);
		AABB box = box(a);
		for (ServerPlayer p : alive) {
			if (!dim(p).equals(dimOf(a))) {
				out(server, r, p, "You left the arena.", true);
				continue;
			}
			if (a.type.equals("colorfloor") && p.getY() < a.corner.y - 3) {
				out(server, r, p, "You fell!", true);
				continue;
			}
			if (!box.inflate(1).contains(p.position())) {
				if (survival) {
					out(server, r, p, "You left the arena.", true);
					continue;
				}
				Games.Pos s = spawnOf(a);
				p.teleportTo(level, s.x, s.y, s.z, Set.of(), s.yaw, 0, true);
				p.sendOverlayMessage(Component.literal("Stay in the arena (exit pads or /game leave take you out)").withStyle(ChatFormatting.RED));
			}
		}
		alive = alive(server, r);
		if (survival) for (ServerPlayer p : alive) r.score.put(p.getUUID(), a.type.equals("anvils") ? r.elapsed() / 1000 : (long) r.colours);
		if (alive.isEmpty()) {
			end(server, r, null);
			return;
		}
		if (survival && r.startedWith > 1 && alive.size() == 1) {
			end(server, r, null);
			return;
		}
		if (a.timeLimit > 0 && r.elapsed() >= a.timeLimit * 1000L) {
			end(server, r, "Time's up!");
			return;
		}
		switch (a.type) {
			case "ghasts" -> ghasts(server, level, r, alive);
			case "colorfloor" -> {
				colorFloor(server, level, r, alive);
				if (a.peacefulFight && server.getTickCount() % 20 == 0) {
					for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, box(a).inflate(8), e -> e.isAlive() && e.entityTags().contains(r.tag()))) {
						if (!(m instanceof net.minecraft.world.entity.Mob mob)) continue;
						Aggro.make(mob, 3f);
						if (mob.getTarget() == null && !alive.isEmpty()) mob.setTarget(alive.get(0));
					}
				}
			}
			case "anvils" -> anvils(level, r, alive);
			case "treasure" -> treasure(level, r, alive);
			default -> {
			}
		}
		if (server.getTickCount() % 10 == 0) hud(server, r, alive);
	}

	private static boolean survival(Arena a) {
		return a.type.equals("colorfloor") || a.type.equals("anvils");
	}

	private static void hud(MinecraftServer server, Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		UUID lead = null;
		for (ServerPlayer p : alive) if (lead == null || r.score.getOrDefault(p.getUUID(), 0L) > r.score.getOrDefault(lead, 0L)) lead = p.getUUID();
		String time = a.timeLimit > 0 ? GameRuntime.clock(Math.max(0, a.timeLimit * 1000L - r.elapsed())).replaceAll("\\.\\d$", "") + " left" : GameRuntime.clock(r.elapsed()).replaceAll("\\.\\d$", "");
		String extra = a.type.equals("colorfloor") ? " · " + String.format(Locale.ROOT, "%.1f", r.seconds) + " s a colour" : "";
		float progress = a.timeLimit > 0 ? 1f - r.elapsed() / (a.timeLimit * 1000f) : 1f;
		if (!a.type.equals("colorfloor") || r.called < 0) {
			bar(server, r, Component.literal(a.shown() + " · " + time + " · " + alive.size() + (alive.size() == 1 ? " player" : " players") + extra), progress, BossEvent.BossBarColor.BLUE);
		}
		for (ServerPlayer p : alive) {
			long mine = r.score.getOrDefault(p.getUUID(), 0L);
			String leader = alive.size() > 1 && lead != null && !lead.equals(p.getUUID()) ? " · leader " + r.names.get(lead) + " " + r.score.getOrDefault(lead, 0L) : "";
			p.sendOverlayMessage(Component.literal(Games.scoreText(a.type, mine, GameRuntime::clock) + leader).withStyle(ChatFormatting.AQUA));
		}
	}

	private static List<ServerPlayer> alive(MinecraftServer server, Round r) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : r.players) {
			if (!r.in(id)) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && p.isAlive()) out.add(p);
		}
		return out;
	}

	// ---------------------------------------------------------------- ghast deflect

	private static void ghasts(MinecraftServer server, ServerLevel level, Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		AABB box = box(a);
		List<LivingEntity> ghasts = level.getEntitiesOfClass(LivingEntity.class, box.inflate(32), e -> e.isAlive() && e.entityTags().contains(r.tag()));
		// One new ghast every half second, up to the cap.
		if (server.getTickCount() % 10 == 0 && ghasts.size() < a.maxAlive) {
			List<MobLines.Entry> pool = a.pool.stream().map(MobLines::parse).filter(e -> e != null).toList();
			if (!pool.isEmpty()) {
				MobLines.Entry e = pool.get(random.nextInt(pool.size()));
				Games.Pos at = a.mobSpawns.get(random.nextInt(a.mobSpawns.size()));
				MobLines.summon(server, at.dim, at.x, at.y, at.z, new MobLines.Entry(e.id(), 1, e.nbt()), "Tags:[\"" + r.tag() + "\"]");
			}
		}
		if (server.getTickCount() % 20 == 0) {
			for (LivingEntity g : ghasts) {
				if (!box.inflate(4).contains(g.position())) {
					Games.Pos at = a.mobSpawns.get(random.nextInt(a.mobSpawns.size()));
					g.teleportTo(at.x, at.y, at.z);
				}
			}
			// Kit rounds keep the snowballs coming.
			if (kit(a)) {
				for (ServerPlayer p : alive) {
					int have = p.getInventory().countItem(Items.SNOWBALL);
					if (have < 8) GameClaims.giveKitItem(p, new ItemStack(Items.SNOWBALL, 16 - have), "quick");
				}
			}
		}
	}

	/** Every tick: a player's snowball that meets a fireball sends it back where they look, as theirs. */
	private static void deflect(MinecraftServer server) {
		for (Round r : rounds.values()) {
			if (r.state != State.PLAYING || !r.arena.type.equals("ghasts")) continue;
			ServerLevel level = level(server, r.arena);
			if (level == null) continue;
			AABB area = box(r.arena).inflate(16);
			List<LargeFireball> fireballs = level.getEntitiesOfClass(LargeFireball.class, area, f -> f.isAlive() && !(f.getOwner() instanceof ServerPlayer));
			if (fireballs.isEmpty()) continue;
			for (Snowball ball : level.getEntitiesOfClass(Snowball.class, area, s -> s.isAlive() && s.getOwner() instanceof ServerPlayer)) {
				ServerPlayer p = (ServerPlayer) ball.getOwner();
				if (!r.in(p.getUUID())) continue;
				Vec3 from = ball.position();
				Vec3 to = from.add(ball.getDeltaMovement());
				for (LargeFireball f : fireballs) {
					if (!f.isAlive() || distanceToSegment(f.position(), from, to) > 1.6) continue;
					f.setOwner(p);
					Vec3 aim = p.getLookAngle().normalize();
					f.setDeltaMovement(aim.scale(1.2));
					f.accelerationPower = 0.1;
					f.needsSync = true;
					ball.discard();
					level.sendParticles(ParticleTypes.CRIT, f.getX(), f.getY(), f.getZ(), 12, 0.3, 0.3, 0.3, 0.2);
					level.playSound(null, f.blockPosition(), SoundEvents.SNOW_GOLEM_SHOOT, SoundSource.PLAYERS, 1f, 0.6f);
					break;
				}
			}
		}
	}

	private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double len = ab.lengthSqr();
		double t = len == 0 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len));
		return p.distanceTo(a.add(ab.scale(t)));
	}

	// ---------------------------------------------------------------- colour floor

	/** Platform i (0–8, row by row) from the grid's corner. */
	private static BlockPos platform(Arena a, int i) {
		int step = a.platformSize + a.platformGap;
		return BlockPos.containing(a.corner.x + (i % 3) * step, a.corner.y, a.corner.z + (i / 3) * step);
	}

	/** Lays all nine platforms, colours shuffled (fewer colours than platforms repeat). */
	static void buildGrid(ServerLevel level, Round r) {
		Arena a = r.arena;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < 9; i++) order.add(i % a.colors.size());
		Collections.shuffle(order, random);
		for (int i = 0; i < 9; i++) {
			r.platform[i] = order.get(i);
			r.gone[i] = false;
			fillPlatform(level, a, i, block(a.colors.get(r.platform[i])));
		}
	}

	/** Builds the grid for the admin to look at (/game edit … build), in the first colours. */
	static void previewGrid(ServerLevel level, Arena a) {
		for (int i = 0; i < 9; i++) fillPlatform(level, a, i, block(a.colors.get(i % a.colors.size())));
	}

	private static void fillPlatform(ServerLevel level, Arena a, int i, Block block) {
		BlockPos c = platform(a, i);
		for (int dx = 0; dx < a.platformSize; dx++) for (int dz = 0; dz < a.platformSize; dz++) level.setBlock(c.offset(dx, 0, dz), block.defaultBlockState(), 3);
	}

	private static void colorFloor(MinecraftServer server, ServerLevel level, Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		long now = System.currentTimeMillis();
		if (r.refillAt > 0) {
			if (now < r.refillAt) return;
			r.refillAt = 0;
			buildGrid(level, r);
			r.seconds = Math.max(a.minRoundSeconds, r.seconds - a.speedUp);
			r.vanished = 0;
			r.nextCallAt = now + 1500;
			for (ServerPlayer p : alive) {
				p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Faster!").withStyle(ChatFormatting.RED)));
				p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(String.format(Locale.ROOT, "%.1f s a colour", r.seconds)).withStyle(ChatFormatting.GRAY)));
			}
			level.playSound(null, platform(a, 4), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1f, 1.2f);
			return;
		}
		if (r.called < 0) {
			if (now < r.nextCallAt) return;
			Set<Integer> left = new HashSet<>();
			for (int i = 0; i < 9; i++) if (!r.gone[i]) left.add(r.platform[i]);
			// One colour left (or none): the grid comes back first.
			if (left.size() <= 1) {
				r.refillAt = now;
				return;
			}
			List<Integer> pick = new ArrayList<>(left);
			r.called = pick.get(random.nextInt(pick.size()));
			r.callAt = now + Math.round(r.seconds * 1000);
			Component name = colourName(a, r.called);
			for (ServerPlayer p : alive) {
				p.connection.send(new ClientboundSetTitleTextPacket(name));
				p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("vanishes in " + String.format(Locale.ROOT, "%.0f", Math.ceil(r.seconds)) + " s · get off it!").withStyle(ChatFormatting.GRAY)));
			}
			level.playSound(null, platform(a, 4), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 1f);
		}
		long left = r.callAt - now;
		bar(server, r, Component.empty().append(colourName(a, r.called)).append(Component.literal(" vanishes in " + String.format(Locale.ROOT, "%.1f", Math.max(0, left) / 1000.0) + " s").withStyle(ChatFormatting.WHITE)),
				Math.max(0f, left / (float) (r.seconds * 1000)), BossEvent.BossBarColor.RED);
		if (left > 0) {
			if (left < 1500 && server.getTickCount() % 6 == 0) level.playSound(null, platform(a, 4), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
			return;
		}
		for (int i = 0; i < 9; i++) {
			if (r.gone[i] || r.platform[i] != r.called) continue;
			r.gone[i] = true;
			fillPlatform(level, a, i, Blocks.AIR);
		}
		r.called = -1;
		r.colours++;
		r.vanished++;
		r.nextCallAt = now + 1500;
		level.playSound(null, platform(a, 4), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 0.8f);
		if (r.vanished >= a.refillEvery) r.refillAt = now + 2000;
		// A mob now and then, on a platform that's still there.
		if (a.mobsEvery > 0 && r.colours % a.mobsEvery == 0 && !a.pool.isEmpty()) {
			List<Integer> standing = new ArrayList<>();
			for (int i = 0; i < 9; i++) if (!r.gone[i]) standing.add(i);
			long mobs = level.getEntitiesOfClass(LivingEntity.class, box(a).inflate(8), e -> e.isAlive() && e.entityTags().contains(r.tag())).size();
			MobLines.Entry e = MobLines.parse(a.pool.get(random.nextInt(a.pool.size())));
			if (e != null && !standing.isEmpty() && mobs < a.maxAlive) {
				BlockPos c = platform(a, standing.get(random.nextInt(standing.size())));
				MobLines.summon(server, a.corner.dim, c.getX() + a.platformSize / 2.0, c.getY() + 1, c.getZ() + a.platformSize / 2.0, new MobLines.Entry(e.id(), 1, e.nbt()), "Tags:[\"" + r.tag() + "\"]");
			}
		}
	}

	/** The colour's block name in its own colour ("Orange Wool"). */
	private static Component colourName(Arena a, int colour) {
		Block b = block(a.colors.get(Math.max(0, Math.min(a.colors.size() - 1, colour))));
		MutableComponent name = b.getName().copy();
		return name.withStyle(st -> st.withColor(TextColor.fromRgb(b.defaultMapColor().col)).withBold(true));
	}

	// ---------------------------------------------------------------- anvil rain

	private static void anvils(ServerLevel level, Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		AABB box = box(a);
		long now = System.currentTimeMillis();
		// Anvils that landed: gone after a moment.
		for (Iterator<FallingBlockEntity> it = r.falling.iterator(); it.hasNext();) {
			FallingBlockEntity f = it.next();
			if (f.isRemoved()) {
				r.landed.put(f.blockPosition(), now + 1000);
				it.remove();
			}
		}
		for (Iterator<Map.Entry<BlockPos, Long>> it = r.landed.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, Long> e = it.next();
			if (e.getValue() > now) continue;
			if (level.getBlockState(e.getKey()).getBlock() instanceof AnvilBlock) level.setBlock(e.getKey(), Blocks.AIR.defaultBlockState(), 3);
			it.remove();
		}
		// More each 10 seconds; half of them aimed near a player.
		double rate = a.anvilRate * Math.pow(1 + a.anvilGrowth / 100.0, r.elapsed() / 10_000.0);
		r.due += rate * 0.1;
		int top = (int) Math.floor(box.maxY) - 1;
		while (r.due >= 1) {
			r.due -= 1;
			double x, z;
			if (!alive.isEmpty() && random.nextBoolean()) {
				ServerPlayer near = alive.get(random.nextInt(alive.size()));
				x = near.getX() + random.nextGaussian() * 2;
				z = near.getZ() + random.nextGaussian() * 2;
			} else {
				x = box.minX + random.nextDouble() * (box.maxX - box.minX);
				z = box.minZ + random.nextDouble() * (box.maxZ - box.minZ);
			}
			x = Math.max(box.minX, Math.min(box.maxX - 0.01, x));
			z = Math.max(box.minZ, Math.min(box.maxZ - 0.01, z));
			BlockPos at = BlockPos.containing(x, top, z);
			if (!level.getBlockState(at).isAir()) continue;
			FallingBlockEntity f = FallingBlockEntity.fall(level, at, Blocks.ANVIL.defaultBlockState());
			f.setHurtsEntities(2f, 40);
			f.disableDrop();
			f.addTag(r.tag());
			r.falling.add(f);
		}
	}

	// ---------------------------------------------------------------- treasure grab

	/** "minecraft:diamond=5" lines as item → points. */
	private static Map<Item, Integer> treasures(Arena a) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		for (String line : a.treasureItems) {
			String[] kv = line.split("=", 2);
			Identifier id = Identifier.tryParse(kv[0].strip().contains(":") ? kv[0].strip() : "minecraft:" + kv[0].strip());
			Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
			int points = 1;
			try {
				if (kv.length > 1) points = Math.max(1, Integer.parseInt(kv[1].strip()));
			} catch (NumberFormatException e) {
				points = 1;
			}
			if (item != Items.AIR) out.put(item, points);
		}
		return out;
	}

	private static void treasure(ServerLevel level, Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		// Picked up: walking into one.
		for (Iterator<Map.Entry<UUID, Integer>> it = r.treasure.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Integer> e = it.next();
			Entity item = level.getEntity(e.getKey());
			if (item == null || !item.isAlive()) {
				it.remove();
				continue;
			}
			for (ServerPlayer p : alive) {
				if (p.distanceToSqr(item) > 1.8 * 1.8) continue;
				long n = r.score.merge(p.getUUID(), (long) e.getValue(), Long::sum);
				p.sendOverlayMessage(Component.literal("+" + e.getValue() + " · " + n + (n == 1 ? " point" : " points")).withStyle(ChatFormatting.GOLD));
				level.playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f + e.getValue() * 0.05f);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, item.getX(), item.getY() + 0.3, item.getZ(), 8, 0.3, 0.3, 0.3, 0);
				item.discard();
				it.remove();
				break;
			}
		}
		if (r.treasure.size() >= a.maxAlive) return;
		Map<Item, Integer> kinds = treasures(a);
		if (kinds.isEmpty()) return;
		// Rarer the more it's worth.
		double total = 0;
		for (int pts : kinds.values()) total += 1.0 / pts;
		double roll = random.nextDouble() * total;
		Item pick = null;
		int points = 1;
		for (Map.Entry<Item, Integer> e : kinds.entrySet()) {
			roll -= 1.0 / e.getValue();
			pick = e.getKey();
			points = e.getValue();
			if (roll <= 0) break;
		}
		BlockPos spot = floorSpot(level, box(a));
		if (spot == null || pick == null) return;
		ItemEntity item = new ItemEntity(level, spot.getX() + 0.5, spot.getY() + 0.2, spot.getZ() + 0.5, new ItemStack(pick));
		item.setNeverPickUp();
		item.setUnlimitedLifetime();
		item.setGlowingTag(true);
		item.setDeltaMovement(0, 0.2, 0);
		item.addTag(r.tag());
		level.addFreshEntity(item);
		r.treasure.put(item.getUUID(), points);
	}

	/** A random spot in the box with ground under it and room above (null after a few misses). */
	private static BlockPos floorSpot(ServerLevel level, AABB box) {
		for (int tries = 0; tries < 8; tries++) {
			int x = (int) Math.floor(box.minX + random.nextDouble() * (box.maxX - box.minX));
			int z = (int) Math.floor(box.minZ + random.nextDouble() * (box.maxZ - box.minZ));
			for (int y = (int) Math.floor(box.maxY) - 2; y > (int) Math.floor(box.minY); y--) {
				BlockPos at = new BlockPos(x, y, z);
				if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).isSolidRender()) return at;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- fishing frenzy

	/**
	 * From the fishing mixin: what a player in a fishing round caught scores points instead (true: the catch is
	 * used up, nothing lands).
	 */
	public static boolean fished(ServerPlayer p, List<ItemStack> loot) {
		Round r = playing.get(p.getUUID());
		if (r == null || !r.arena.type.equals("fishing") || r.state != State.PLAYING) return false;
		int points = 0;
		List<String> what = new ArrayList<>();
		for (ItemStack st : loot) {
			Item i = st.getItem();
			int pts = i == Items.COD || i == Items.SALMON ? r.arena.fishPoints
					: i == Items.TROPICAL_FISH || i == Items.PUFFERFISH ? r.arena.rareFishPoints
					: i == Items.ENCHANTED_BOOK || i == Items.NAME_TAG || i == Items.NAUTILUS_SHELL || i == Items.SADDLE || i == Items.BOW || st.isEnchanted() ? r.arena.treasurePoints
					: r.arena.junkPoints;
			points += pts;
			what.add(st.getHoverName().getString());
		}
		long n = r.score.merge(p.getUUID(), (long) points, Long::sum);
		p.sendOverlayMessage(Component.literal(String.join(", ", what) + ": +" + points + " · " + n + (n == 1 ? " point" : " points")).withStyle(points > 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		p.level().playSound(null, p.blockPosition(), points >= r.arena.treasurePoints && points > 0 ? SoundEvents.PLAYER_LEVELUP : SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.7f, 1.2f);
		return true;
	}

	// ---------------------------------------------------------------- out and end

	/** A player leaves the round; counts = their score is kept (they played it out), else it's dropped. */
	private static void out(MinecraftServer server, Round r, ServerPlayer p, String why, boolean counts) {
		UUID id = p.getUUID();
		if (!r.in(id)) return;
		r.out.add(id);
		playing.remove(id);
		onExit.remove(id);
		if (r.bar != null) r.bar.removePlayer(p);
		if (counts) {
			r.counted.add(id);
			if (why != null) p.sendSystemMessage(Component.literal(why + " " + Games.scoreText(r.arena.type, r.score.getOrDefault(id, 0L), GameRuntime::clock) + ".").withStyle(ChatFormatting.YELLOW));
			result(server, r, p);
		} else if (why != null) {
			p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
		}
		finishFor(server, r.arena, p);
		// Out but not gone: they watch the rest of the round from the spectator area.
		if (counts && r.state == State.PLAYING && r.arena.spectate != null && toSpectate(server, r.arena, p)) {
			r.watching.add(id);
			p.sendSystemMessage(Component.literal("Watch the rest of the round from here.").withStyle(ChatFormatting.GRAY));
		}
		if (r.state == State.PLAYING && r.players.stream().noneMatch(r::in)) end(server, r, null);
	}

	/** Their score is kept: personal best, finish commands and Rewards triggers. */
	private static void result(MinecraftServer server, Round r, ServerPlayer p) {
		Arena a = r.arena;
		long score = r.score.getOrDefault(p.getUUID(), 0L);
		if (score <= 0) return;
		boolean record = GameRuntime.recordScore(a, p, score);
		String shown = Games.scoreText(a.type, score, GameRuntime::clock);
		if (record) p.sendSystemMessage(Component.literal("New personal best in " + a.shown() + ": " + shown + "!").withStyle(ChatFormatting.GOLD));
		if (!a.finishMessage.isBlank()) p.sendSystemMessage(Component.literal(fill(a.finishMessage, a, p, String.valueOf(score))).withStyle(ChatFormatting.YELLOW));
		commands(server, a.finishCommands, a, p, String.valueOf(score));
		RewardsControl.fireGame(server, "gameFinished", p, a.name, score, false);
		GameRuntime.points(p, a, score, false);
		if (record) RewardsControl.fireGame(server, "gameRecord", p, a.name, score, false);
	}

	/** Their things back, out of adventure mode, and to the game hub spawn (else the start point). */
	private static void finishFor(MinecraftServer server, Arena a, ServerPlayer p) {
		if (kit(a)) GameClaims.restoreFor(p, true);
		ServerLevel level = a.start == null ? null : GameRuntime.levelOf(server, a.start.dim);
		// To the game hub spawn (else the start point).
		if (p.isAlive() && !Portals.returnTo(server, p, a) && level != null) p.teleportTo(level, a.start.x, a.start.y, a.start.z, Set.of(), a.start.yaw, 0, true);
		p.resetFallDistance();
		cooldown.put(p.getUUID(), System.currentTimeMillis() + Math.max(5, a.cooldown) * 1000L);
		GameClaims.gameMode(p, false);
	}

	/** The round is over: everyone still in counts, the ranking goes to everyone who played, the box is cleaned. */
	private static void end(MinecraftServer server, Round r, String why) {
		if (r.state == State.ENDED) return;
		boolean wasPlaying = r.state == State.PLAYING;
		r.state = State.ENDED;
		rounds.remove(r.arena.id);
		if (r.bar != null) r.bar.removeAllPlayers();
		Arena a = r.arena;
		List<ServerPlayer> still = alive(server, r);
		if (wasPlaying) {
			// Survival rounds: whoever is left won it.
			if (survival(a) && r.startedWith > 1 && still.size() == 1) {
				ServerPlayer w = still.get(0);
				w.connection.send(new ClientboundSetTitleTextPacket(Component.literal("You win!").withStyle(ChatFormatting.GOLD)));
			}
			for (ServerPlayer p : still) {
				if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
				r.out.add(p.getUUID());
				playing.remove(p.getUUID());
				onExit.remove(p.getUUID());
				r.counted.add(p.getUUID());
				result(server, r, p);
				finishFor(server, a, p);
			}
			ranking(server, r);
		}
		for (UUID id : r.players) playing.remove(id);
		clearBox(server, r);
		// Those who watched go to the game hub spawn (unless they queued for the next round).
		for (UUID id : r.watching) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || r.next.contains(id) || !inSpectate(a, p, 3)) continue;
			ServerLevel level = a.start == null ? null : GameRuntime.levelOf(server, a.start.dim);
			if (!Portals.returnTo(server, p, a) && level != null) p.teleportTo(level, a.start.x, a.start.y, a.start.z, Set.of(), a.start.yaw, 0, true);
		}
		// The queue becomes the next round's join window.
		if (wasPlaying && !r.next.isEmpty() && GameRuntime.settings().enabled && a.enabled) {
			Round n = new Round(a);
			n.startsAt = System.currentTimeMillis() + Math.max(5, a.joinSeconds) * 1000L;
			for (UUID id : r.next) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p == null) continue;
				n.players.add(id);
				n.names.put(id, p.getGameProfile().name());
				p.sendSystemMessage(Component.literal("▶ The next round of " + a.shown() + " starts in " + Math.max(5, a.joinSeconds) + " s. Others can still step on the pad to join.").withStyle(ChatFormatting.GREEN));
			}
			if (!n.players.isEmpty()) rounds.put(a.id, n);
		}
		if (wasPlaying) NiceControlCenter.LOGGER.info("Games: quick round {} ended: {}", a.name, String.join(", ", r.counted.stream().map(id -> r.names.get(id) + " " + r.score.getOrDefault(id, 0L)).toList()));
	}

	/** The top three, to everyone who played. */
	private static void ranking(MinecraftServer server, Round r) {
		if (r.counted.isEmpty()) return;
		List<UUID> order = new ArrayList<>(r.counted);
		// Higher first; equal scores: whoever lasted longer (went out later) first.
		order.sort((x, y) -> {
			int c = Long.compare(r.score.getOrDefault(y, 0L), r.score.getOrDefault(x, 0L));
			return c != 0 ? c : Integer.compare(r.counted.indexOf(y), r.counted.indexOf(x));
		});
		MutableComponent line = Component.literal("■ " + r.arena.shown() + " · ").withStyle(ChatFormatting.GOLD);
		String[] medal = { "1st", "2nd", "3rd" };
		for (int i = 0; i < Math.min(3, order.size()); i++) {
			UUID id = order.get(i);
			if (i > 0) line.append(Component.literal("  ").withStyle(ChatFormatting.GRAY));
			line.append(Component.literal(medal[i] + " " + r.names.get(id) + " ").withStyle(i == 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
					.append(Component.literal(Games.scoreText(r.arena.type, r.score.getOrDefault(id, 0L), GameRuntime::clock)).withStyle(ChatFormatting.GRAY));
		}
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) p.sendSystemMessage(line);
		}
	}

	/** The round's mobs, anvils and treasure gone; the colour floor laid out again. */
	private static void clearBox(MinecraftServer server, Round r) {
		Arena a = r.arena;
		ServerLevel level = level(server, a);
		if (level == null) return;
		AABB box = box(a);
		for (Entity e : level.getEntitiesOfClass(Entity.class, box.inflate(32), e -> e.entityTags().contains(r.tag()))) e.discard();
		ConsoleRunner.quiet(server, "kill @e[tag=" + r.tag() + "]");
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof FallingBlockEntity || e instanceof LargeFireball || e instanceof Snowball)) e.discard();
		r.treasure.clear();
		r.falling.clear();
		if (a.type.equals("anvils")) {
			// Landed anvils anywhere in the box.
			for (BlockPos b : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
				if (level.getBlockState(b).getBlock() instanceof AnvilBlock) level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
			}
			r.landed.clear();
		}
		if (a.type.equals("colorfloor") && a.corner != null) buildGrid(level, r);
	}

	// ---------------------------------------------------------------- helpers

	/** The play box: both corners included; a colour floor's own box around its grid when it has none. */
	static AABB box(Arena a) {
		if (a.type.equals("colorfloor") && a.corner != null && (a.areaMin == null || a.areaMax == null)) {
			int span = a.platformSize * 3 + a.platformGap * 2;
			return new AABB(a.corner.x - 2, a.corner.y - 8, a.corner.z - 2, a.corner.x + span + 2, a.corner.y + 12, a.corner.z + span + 2);
		}
		if (a.areaMin == null || a.areaMax == null) return new AABB(0, 0, 0, 0, 0, 0);
		return new AABB(Math.min(a.areaMin.x, a.areaMax.x), Math.min(a.areaMin.y, a.areaMax.y), Math.min(a.areaMin.z, a.areaMax.z),
				Math.max(a.areaMin.x, a.areaMax.x) + 1, Math.max(a.areaMin.y, a.areaMax.y) + 1, Math.max(a.areaMin.z, a.areaMax.z) + 1);
	}

	/** Close to the start point, or in the spectator area (where the queue waits). */
	private static boolean nearPad(Arena a, ServerPlayer p) {
		if (a.start != null && dim(p).equals(a.start.dim) && p.distanceToSqr(a.start.x, a.start.y, a.start.z) <= LOBBY_RANGE * LOBBY_RANGE) return true;
		return a.spectate != null && inSpectate(a, p, 3);
	}

	private static boolean inSpectate(Arena a, ServerPlayer p, double slack) {
		Games.Pos s = a.spectate;
		double r = a.spectateRadius + slack;
		return s != null && dim(p).equals(s.dim) && p.distanceToSqr(s.x, s.y, s.z) <= r * r;
	}

	/** To the spectator area (false when its dimension is gone). */
	private static boolean toSpectate(MinecraftServer server, Arena a, ServerPlayer p) {
		Games.Pos s = a.spectate;
		ServerLevel level = s == null ? null : GameRuntime.levelOf(server, s.dim);
		if (level == null || !p.isAlive()) return false;
		p.teleportTo(level, s.x, s.y, s.z, Set.of(), s.yaw, 0, true);
		p.resetFallDistance();
		return true;
	}

	/** Where players start: the spawn, or a colour floor's middle platform. */
	private static Games.Pos spawnOf(Arena a) {
		if (a.spawn != null || !a.type.equals("colorfloor") || a.corner == null) return a.spawn;
		BlockPos mid = platform(a, 4);
		return new Games.Pos(a.corner.dim, mid.getX() + a.platformSize / 2.0, mid.getY() + 1, mid.getZ() + a.platformSize / 2.0, 0);
	}

	private static String dimOf(Arena a) {
		return a.type.equals("colorfloor") && a.corner != null ? a.corner.dim : a.spawn != null ? a.spawn.dim : a.start.dim;
	}

	private static ServerLevel level(MinecraftServer server, Arena a) {
		return GameRuntime.levelOf(server, dimOf(a));
	}

	private static void bar(MinecraftServer server, Round r, Component text, float progress, BossEvent.BossBarColor color) {
		if (r.bar == null) r.bar = new ServerBossEvent(UUID.randomUUID(), text, color, BossEvent.BossBarOverlay.PROGRESS);
		r.bar.setName(text);
		r.bar.setColor(color);
		r.bar.setProgress(Math.max(0f, Math.min(1f, progress)));
		for (UUID id : r.players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && (r.state == State.LOBBY || r.in(id))) r.bar.addPlayer(p);
		}
	}

	/** Start pads of quick rounds sparkle gold when someone's close; exit pads red while a round is on. */
	private static void particles(MinecraftServer server) {
		double turn = server.getTickCount() / 20.0 * 0.4;
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || !Games.quick(a.type)) continue;
			if (a.start != null) ring(server, a.start, new DustParticleOptions(0xFFC83D, 1.2f), turn);
			if (rounds.containsKey(a.id)) for (Games.Pos pad : a.exitPads) ring(server, pad, new DustParticleOptions(0xFF4A4A, 1.2f), turn);
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

	private static Block block(String id) {
		Identifier i = Identifier.tryParse(id);
		Block b = i == null ? null : BuiltInRegistries.BLOCK.getValue(i);
		return b == null || b == Blocks.AIR ? Blocks.STONE : b;
	}

	private static String fill(String text, Arena a, ServerPlayer p, String score) {
		return Games.math(text, Map.of("score", score)).replace("{player}", p.getGameProfile().name()).replace("{game}", a.shown()).replace("{score}", score);
	}

	private static void commands(MinecraftServer server, List<String> lines, Arena a, ServerPlayer p, String score) {
		for (String line : lines) {
			String cmd = fill(line.strip().replaceFirst("^/", ""), a, p, score);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
		}
	}

	private static String dim(ServerPlayer p) {
		return p.level().dimension().identifier().toString();
	}
}
