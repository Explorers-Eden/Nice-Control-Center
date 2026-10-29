package eu.explorerseden.nicecontrolcenter.rewards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Rewards;
import eu.explorerseden.nicecontrolcenter.players.Perks;
import eu.explorerseden.nicecontrolcenter.core.WorldClock;

/** Runs the event rewards (see {@link Rewards}): hooks for each trigger, "once" bookkeeping and the commands. */
public final class RewardsControl {
	private static volatile Rewards.Settings settings = new Rewards.Settings().fill();
	private static long modified = -1;
	private static Rewards.State state = new Rewards.State();
	private static Path stateFile;
	private static boolean dirty;
	/** Last seen XP level per player, to notice a level being reached. */
	private static final Map<UUID, Integer> levels = new HashMap<>();
	/** Whether each player was in one of their claims at the last look, for enterClaim / leaveClaim. */
	private static final Map<UUID, Boolean> inClaim = new HashMap<>();
	private static double lastMinute = -1;
	private static String lastDate;
	/** Interval rules: when each last ran (counting starts when the server starts or the rule is added). */
	private static final Map<String, Long> lastInterval = new HashMap<>();

	private RewardsControl() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Rewards.FILE);
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			stateFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-rewards-state.json");
			try {
				Rewards.State s = Files.exists(stateFile) ? Json.GSON.fromJson(Files.readString(stateFile), Rewards.State.class) : null;
				state = s != null && s.ran != null ? s : new Rewards.State();
				if (state.waiting == null) state.waiting = new LinkedHashMap<>();
			} catch (IOException | RuntimeException e) {
				state = new Rewards.State();
			}
			lastMinute = -1;
			lastDate = null;
			lastInterval.clear();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) reload();
			if (server.getTickCount() % 20 == 0) everySecond(server);
			if (dirty && server.getTickCount() % 200 == 0) save();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			fire(server, "leave", handler.player, null);
			levels.remove(handler.player.getUUID());
			inClaim.remove(handler.player.getUUID());
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) ->
				fire(to.getServer(), "dimension", player, to.dimension().identifier().toString()));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			MinecraftServer server = entity.level().getServer();
			if (server == null) return;
			if (entity instanceof ServerPlayer p) fire(server, "death", p, null);
			if (source.getEntity() instanceof ServerPlayer killer && killer != entity) {
				String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
				fire(server, "kill", killer, type);
			}
		});
	}

	/** From the join hook, which knows whether it's the player's very first visit. */
	public static void joined(MinecraftServer server, ServerPlayer player, boolean first) {
		levels.put(player.getUUID(), player.experienceLevel);
		if (first) fire(server, "firstJoin", player, null);
		fire(server, "join", player, null);
	}

	/** From the advancement mixin: a real advancement (with a display, not a recipe) was completed. */
	public static void advancement(ServerPlayer player, String id) {
		MinecraftServer server = player.level().getServer();
		if (server != null) fire(server, "advancement", player, id);
	}

	public static void reload() {
		Path f = file();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(f);
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		settings = Rewards.read(f);
		if (!first) NiceControlCenter.LOGGER.info("Rewards: reloaded the event rewards ({} rules)", settings.rules.size());
	}

	private static void everySecond(MinecraftServer server) {
		Rewards.Settings s = settings;
		if (!s.enabled || s.rules.isEmpty()) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean in = Perks.inOwnClaim(p);
			Boolean was = inClaim.put(p.getUUID(), in);
			if (was != null && was != in) {
				var claim = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.claimAt(p.level(), p.blockPosition());
				fire(server, in ? "enterClaim" : "leaveClaim", p, claim != null ? claim.displayName() : "");
			}
			// Rules given with /reward give that waited for this player.
			List<String> waits = state.waiting.get(p.getStringUUID());
			if (waits != null && !waits.isEmpty()) {
				for (String id : List.copyOf(waits)) {
					Rewards.Rule r = s.rules.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
					if (r != null && r.claimsOnly && !in) continue;
					waits.remove(id);
					dirty = true;
					if (r != null && r.enabled) run(server, r, p, r.name);
				}
				if (waits.isEmpty()) state.waiting.remove(p.getStringUUID());
			}
			int level = p.experienceLevel;
			Integer before = levels.put(p.getUUID(), level);
			if (before != null && level > before) {
				for (Rewards.Rule r : s.rules) {
					if (r.enabled && r.trigger.equals("level") && before < r.number && level >= r.number) run(server, r, p, String.valueOf(r.number));
				}
			}
			int hours = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 72_000;
			for (Rewards.Rule r : s.rules) {
				// Playtime only counts up, so "once per player" is what makes sense; the bookkeeping keeps it from repeating.
				if (r.enabled && r.trigger.equals("playtime") && hours >= r.number && !ranFor(r, p.getUUID().toString())) run(server, r, p, String.valueOf(r.number));
			}
		}
		ServerLevel overworld = server.overworld();
		long day = overworld.getOverworldClockTime() / 24_000;
		// The Nice Actions clock when the pack is installed, else the vanilla one (see WorldClock).
		WorldClock.Clock clock = WorldClock.current();
		double minute = clock != null ? clock.minuteOfDay() : ((overworld.getOverworldClockTime() % 24_000) * 1440.0 / 24_000 + 360) % 1440;
		String date = clock != null ? clock.date() : "";
		String weekday = clock != null && clock.weekday() != null ? clock.weekday() : "";
		double beforeMinute = lastMinute;
		String beforeDate = lastDate;
		lastMinute = minute;
		lastDate = date;
		long now = System.currentTimeMillis();
		for (Rewards.Rule r : s.rules) {
			if (!r.enabled) continue;
			switch (r.trigger) {
				case "dayTime" -> {
					if (beforeMinute < 0) continue;
					double target = Rewards.minuteOf(r);
					if (target < 0) continue;
					// Crossed since the last check, also across midnight (and when the night was skipped).
					boolean crossed = minute >= beforeMinute ? beforeMinute < target && target <= minute : target > beforeMinute || target <= minute;
					if (crossed && minute != beforeMinute) runWorld(server, r, clock != null ? clock.time() : String.valueOf((long) minute));
				}
				case "gameDay" -> {
					if (day >= r.number && !ranFor(r, "*")) runWorld(server, r, String.valueOf(day));
				}
				case "date" -> {
					// Once when the calendar turns to that date (or weekday).
					if (beforeDate == null || date.isEmpty() || date.equals(beforeDate)) continue;
					String want = r.value.strip().toLowerCase(java.util.Locale.ROOT);
					String d = date.toLowerCase(java.util.Locale.ROOT);
					if (d.equals(want) || d.startsWith(want + ",") || weekday.equalsIgnoreCase(want)) runWorld(server, r, date);
				}
				case "interval" -> {
					Long last = lastInterval.get(r.id);
					if (last == null) {
						lastInterval.put(r.id, now);
					} else if (now - last >= r.number * 60_000L) {
						lastInterval.put(r.id, now);
						runWorld(server, r, String.valueOf(r.number));
					}
				}
				default -> {
				}
			}
		}
	}

	private static void fire(MinecraftServer server, String trigger, ServerPlayer player, String value) {
		Rewards.Settings s = settings;
		if (server == null || !s.enabled) return;
		for (Rewards.Rule r : s.rules) {
			if (!r.enabled || !r.trigger.equals(trigger)) continue;
			if (!r.value.isBlank()) {
				String want = r.value.contains(":") ? r.value : "minecraft:" + r.value;
				if (!want.equalsIgnoreCase(value)) continue;
			} else if (trigger.equals("kill") && "minecraft:player".equals(value)) {
				continue;
			}
			run(server, r, player, value);
		}
	}

	/** For game triggers: {score} and {game} while their rules run. */
	private static final ThreadLocal<Map<String, String>> extra = ThreadLocal.withInitial(Map::of);

	/** The {score} of the game trigger whose rule is running (0 when there's none, or it's a time). */
	public static long currentScore() {
		try {
			return Long.parseLong(extra.get().getOrDefault("{score}", "0").replace(",", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * A game run ended (gameFinished) or set a personal best (gameRecord): rules for that arena (value, empty = any)
	 * whose number is met: at least that many jumps, or (timed games) within that many seconds; 0 = any.
	 */
	public static void fireGame(MinecraftServer server, String trigger, ServerPlayer player, String arena, long score, boolean timed) {
		Rewards.Settings s = settings;
		if (server == null || !s.enabled) return;
		String shown = timed ? eu.explorerseden.nicecontrolcenter.games.GameRuntime.clock(score) : String.valueOf(score);
		extra.set(Map.of("{score}", shown, "{game}", arena));
		try {
			for (Rewards.Rule r : s.rules) {
				if (!r.enabled || !r.trigger.equals(trigger)) continue;
				if (!r.value.isBlank() && !r.value.strip().equalsIgnoreCase(arena)) continue;
				if (r.number > 0 && (timed ? score > r.number * 1000L : score < r.number)) continue;
				run(server, r, player, arena);
			}
		} finally {
			extra.remove();
		}
	}

	/** A count that grows (heads found): rules for that game (value, empty = any) whose number divides it (0 = every one). */
	public static void fireEvery(MinecraftServer server, String trigger, ServerPlayer player, String arena, long count) {
		Rewards.Settings s = settings;
		if (server == null || !s.enabled) return;
		extra.set(Map.of("{score}", String.valueOf(count), "{game}", arena));
		try {
			for (Rewards.Rule r : s.rules) {
				if (!r.enabled || !r.trigger.equals(trigger)) continue;
				if (!r.value.isBlank() && !r.value.strip().equalsIgnoreCase(arena)) continue;
				if (r.number > 0 && count % r.number != 0) continue;
				run(server, r, player, arena);
			}
		} finally {
			extra.remove();
		}
	}

	/** World triggers: once with no player, or once per player for commands that use {player}. */
	private static void runWorld(MinecraftServer server, Rewards.Rule r, String value) {
		boolean perPlayer = r.who.equals("player") || r.claimsOnly || r.commands.stream().anyMatch(c -> c.contains("{player}") || c.contains("{uuid}")) || (!r.message.isBlank() && !r.messageEveryone);
		if (!perPlayer) {
			if (r.repeat.equals("once") || r.trigger.equals("gameDay")) {
				if (ranFor(r, "*")) return;
				mark(r, "*");
			}
			execute(server, r, null, value);
			return;
		}
		if (r.trigger.equals("gameDay")) mark(r, "*");
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) run(server, r, p, value);
	}

	private static void run(MinecraftServer server, Rewards.Rule r, ServerPlayer p, String value) {
		if (r.who.equals("player") && !p.getGameProfile().name().equalsIgnoreCase(r.player)) return;
		if (r.claimsOnly && !Perks.inOwnClaim(p)) return;
		String key = p.getUUID().toString();
		long now = System.currentTimeMillis();
		switch (r.repeat) {
			case "once" -> {
				if (ranFor(r, "*")) return;
				mark(r, "*");
			}
			case "oncePerPlayer" -> {
				if (ranFor(r, key)) return;
			}
			default -> {
			}
		}
		if (r.cooldownMinutes > 0) {
			Long last = state.ran.getOrDefault(r.id, Map.of()).get(key);
			if (last != null && now - last < r.cooldownMinutes * 60_000L) return;
		}
		mark(r, key);
		execute(server, r, p, value);
	}

	private static boolean ranFor(Rewards.Rule r, String key) {
		return state.ran.getOrDefault(r.id, Map.of()).containsKey(key);
	}

	private static void mark(Rewards.Rule r, String key) {
		state.ran.computeIfAbsent(r.id, k -> new LinkedHashMap<>()).put(key, System.currentTimeMillis());
		dirty = true;
	}

	public static Rewards.Settings settings() {
		return settings;
	}

	/** The rules that /reward give hands out (trigger "Given with /reward give"). */
	public static List<Rewards.Rule> manualRules() {
		return settings.rules.stream().filter(r -> r.enabled && r.trigger.equals("manual")).toList();
	}

	/** /reward give: runs the rule for the player now, or when they're online / in their claims. Returns what happened. */
	public static String giveManual(MinecraftServer server, UUID uuid, ServerPlayer online, String ruleName) {
		Rewards.Rule r = manualRules().stream().filter(x -> x.name.equalsIgnoreCase(ruleName.strip())).findFirst().orElse(null);
		if (r == null) throw new RewardActions.Refused("there's no reward rule \"" + ruleName + "\" given with /reward give.");
		if (online != null && (!r.claimsOnly || Perks.inOwnClaim(online))) {
			run(server, r, online, r.name);
			return "\"" + r.name + "\" given";
		}
		state.waiting.computeIfAbsent(uuid.toString(), k -> new java.util.ArrayList<>()).add(r.id);
		dirty = true;
		save();
		return "\"" + r.name + "\" waits until they're " + (online == null ? "online" + (r.claimsOnly ? " in their claims" : "") : "in their claims");
	}

	private static void execute(MinecraftServer server, Rewards.Rule r, ServerPlayer p, String value) {
		// Non-item rewards first (they need a player); their lines go into {rewards}.
		List<String> got = new java.util.ArrayList<>();
		if (p != null) {
			for (Rewards.Action a : r.actions) {
				try {
					got.add(RewardActions.give(server, p.getUUID(), p.getGameProfile().name(), p, a, "rule " + r.name));
				} catch (RewardActions.Refused e) {
					NiceControlCenter.LOGGER.info("Rewards: reward \"{}\" for {}: {}", r.name, p.getGameProfile().name(), e.getMessage());
				}
			}
		}
		String rewards = String.join(", ", got);
		run(server, p, r.commands, value, "Reward \"" + r.name + "\"");
		String message = r.message.isBlank() && !got.isEmpty() ? "§6You got: {rewards}" : r.message;
		if (message.isBlank()) return;
		Component msg = Component.literal(fill(server, message.replace("{rewards}", rewards), p, value));
		if (r.messageEveryone || p == null) server.getPlayerList().broadcastSystemMessage(msg, false);
		else p.sendSystemMessage(msg);
	}

	/** Runs commands as the server at the player, with the variables filled in. */
	private static void run(MinecraftServer server, ServerPlayer p, List<String> commands, String value, String what) {
		var source = server.createCommandSourceStack();
		if (p != null) source = source.withEntity(p).withPosition(p.position()).withRotation(p.getRotationVector()).withLevel(p.level());
		for (String c : commands) {
			String cmd = fill(server, c, p, value);
			if (p == null && (cmd.contains("{player}") || cmd.contains("{uuid}"))) continue;
			try {
				server.getCommands().performPrefixedCommand(source, cmd);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Rewards: {}: /{} failed: {}", what, cmd, e.getMessage());
			}
		}
	}

	/** {player} {uuid} {x} {y} {z} {dimension} {value} {day} {time} {date} {online} (and {rewards} in rule messages). */
	public static String fill(MinecraftServer server, String text, ServerPlayer p, String value) {
		for (Map.Entry<String, String> e : extra.get().entrySet()) text = text.replace(e.getKey(), e.getValue());
		long clock = server.overworld().getOverworldClockTime();
		WorldClock.Clock wc = WorldClock.current();
		String out = text.replace("{day}", String.valueOf(clock / 24_000)).replace("{time}", wc != null ? wc.time() : clockText(clock % 24_000))
				.replace("{date}", wc != null ? wc.date() : "Day " + (clock / 24_000 + 1))
				.replace("{online}", String.valueOf(server.getPlayerCount())).replace("{value}", value == null ? "" : value);
		if (p == null) return out;
		return out.replace("{player}", p.getGameProfile().name()).replace("{uuid}", p.getUUID().toString())
				.replace("{x}", String.valueOf(p.getBlockX())).replace("{y}", String.valueOf(p.getBlockY())).replace("{z}", String.valueOf(p.getBlockZ()))
				.replace("{dimension}", p.level().dimension().identifier().toString());
	}

	private static String clockText(long ticks) {
		long minutes = ((ticks + 6000) % 24_000) * 60 / 1000;
		return String.format("%02d:%02d", minutes / 60, minutes % 60);
	}

	private static void save() {
		Path f = stateFile;
		if (f == null || !dirty) return;
		dirty = false;
		try {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(state), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Rewards: could not save the event rewards' state: {}", e.getMessage());
		}
	}

	/** Forget who a rule already ran for (the panel's "Reset"), so "once" rules run again. */
	public static void reset(String ruleId) {
		if (state.ran.remove(ruleId) != null) {
			dirty = true;
			save();
		}
	}
}
