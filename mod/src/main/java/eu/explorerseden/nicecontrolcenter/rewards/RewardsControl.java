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

/** Runs the event rewards (see {@link Rewards}): hooks for each trigger, "once" bookkeeping and the commands. */
public final class RewardsControl {
	private static volatile Rewards.Settings settings = new Rewards.Settings().fill();
	private static long modified = -1;
	private static Rewards.State state = new Rewards.State();
	private static Path stateFile;
	private static boolean dirty;
	/** Last seen XP level per player, to notice a level being reached. */
	private static final Map<UUID, Integer> levels = new HashMap<>();
	private static long lastDayTime = -1;

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
			} catch (IOException | RuntimeException e) {
				state = new Rewards.State();
			}
			lastDayTime = -1;
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
		if (!first) NiceControlCenter.LOGGER.info("Reloaded the event rewards ({} rules)", settings.rules.size());
	}

	private static void everySecond(MinecraftServer server) {
		Rewards.Settings s = settings;
		if (!s.enabled || s.rules.isEmpty()) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
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
		long clock = overworld.getOverworldClockTime();
		long day = clock / 24_000;
		long time = clock % 24_000;
		long before = lastDayTime;
		lastDayTime = clock;
		if (before < 0 || clock < before) return;
		for (Rewards.Rule r : s.rules) {
			if (!r.enabled) continue;
			if (r.trigger.equals("dayTime")) {
				// Crossed the time since the last check (also across midnight, and when the night was skipped).
				long target = (before / 24_000) * 24_000 + r.number;
				if (target <= before) target += 24_000;
				if (target <= clock) runWorld(server, r, String.valueOf(time));
			} else if (r.trigger.equals("gameDay") && day >= r.number && !ranFor(r, "*")) {
				runWorld(server, r, String.valueOf(day));
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

	/** World triggers: once with no player, or once per player for commands that use {player}. */
	private static void runWorld(MinecraftServer server, Rewards.Rule r, String value) {
		boolean perPlayer = r.who.equals("player") || r.commands.stream().anyMatch(c -> c.contains("{player}") || c.contains("{uuid}")) || (!r.message.isBlank() && !r.messageEveryone);
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

	private static void execute(MinecraftServer server, Rewards.Rule r, ServerPlayer p, String value) {
		var source = server.createCommandSourceStack();
		if (p != null) source = source.withEntity(p).withPosition(p.position()).withRotation(p.getRotationVector()).withLevel(p.level());
		for (String c : r.commands) {
			String cmd = fill(server, c, p, value);
			if (p == null && (cmd.contains("{player}") || cmd.contains("{uuid}"))) continue;
			try {
				server.getCommands().performPrefixedCommand(source, cmd);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Reward \"{}\": /{} failed: {}", r.name, cmd, e.getMessage());
			}
		}
		if (r.message.isBlank()) return;
		Component msg = Component.literal(fill(server, r.message, p, value));
		if (r.messageEveryone || p == null) server.getPlayerList().broadcastSystemMessage(msg, false);
		else p.sendSystemMessage(msg);
	}

	/** {player} {uuid} {x} {y} {z} {dimension} {value} {day} {time} {online}. */
	static String fill(MinecraftServer server, String text, ServerPlayer p, String value) {
		long clock = server.overworld().getOverworldClockTime();
		String out = text.replace("{day}", String.valueOf(clock / 24_000)).replace("{time}", clockText(clock % 24_000))
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
			NiceControlCenter.LOGGER.warn("Could not save the event rewards' state: {}", e.getMessage());
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
