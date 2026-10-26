package eu.explorerseden.nicecontrolcenter.players;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.context.CommandContext;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * /ncc fly &lt;players&gt; &lt;time&gt;: creative flight in survival for a while (time like /tick: 100t, 30s, 1d = one
 * in-game day), /ncc fly &lt;players&gt; off ends it. Survives logging out and restarts; when it runs out the player
 * floats down with Slow Falling.
 */
public final class TempFly {
	public static final String PERMISSION = "nicecontrolcenter.fly";
	/** uuid → when the flight ends (epoch millis). */
	private static final Map<UUID, Long> until = new HashMap<>();
	private static Path file;

	private TempFly() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
			for (String root : new String[] { "ncc", "nicecontrolcenter" }) {
				dispatcher.register(Commands.literal(root).then(Commands.literal("fly")
						.requires(Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS))
						.then(Commands.argument("players", EntityArgument.players())
								.then(Commands.literal("off").executes(ctx -> off(ctx, EntityArgument.getPlayers(ctx, "players"))))
								.then(Commands.argument("time", TimeArgument.time(1)).executes(ctx -> grant(ctx, EntityArgument.getPlayers(ctx, "players"),
										ctx.getArgument("time", Integer.class)))))));
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(TempFly::load);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0 && !until.isEmpty()) tick(server);
		});
	}

	private static int grant(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, int ticks) {
		long ms = ticks * 50L;
		long end = System.currentTimeMillis() + ms;
		for (ServerPlayer p : players) {
			until.put(p.getUUID(), end);
			allow(p, true);
			p.sendSystemMessage(Component.literal("You can fly for " + human(ms) + ". Double-tap jump to take off.").withStyle(ChatFormatting.AQUA));
		}
		save();
		ctx.getSource().sendSuccess(() -> Component.literal("Flight for " + (players.size() == 1 ? players.iterator().next().getGameProfile().name() : players.size() + " players")
				+ " for " + human(ms) + " (" + ticks + " ticks)."), true);
		return players.size();
	}

	private static int off(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
		int n = 0;
		for (ServerPlayer p : players) {
			if (until.remove(p.getUUID()) == null) continue;
			land(p);
			n++;
		}
		save();
		int count = n;
		ctx.getSource().sendSuccess(() -> Component.literal(count == 0 ? "Nobody of them had timed flight." : "Flight ended for " + count + " player" + (count == 1 ? "" : "s") + "."), true);
		return n;
	}

	private static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Long end = until.get(p.getUUID());
			if (end == null) continue;
			long left = end - now;
			if (left <= 0) {
				until.remove(p.getUUID());
				land(p);
				changed = true;
				continue;
			}
			// Game mode changes and respawns reset the abilities: give flight back.
			if (!p.getAbilities().mayfly) allow(p, false);
			long s = left / 1000;
			if (s == 60 || s == 10 || (s <= 5 && s > 0)) p.sendOverlayMessage(Component.literal("Flight ends in " + s + " s").withStyle(ChatFormatting.YELLOW));
		}
		if (changed) save();
	}

	private static void allow(ServerPlayer p, boolean announce) {
		if (p.isCreative() || p.isSpectator()) return;
		p.getAbilities().mayfly = true;
		p.onUpdateAbilities();
	}

	private static void land(ServerPlayer p) {
		if (p.isCreative() || p.isSpectator()) return;
		boolean wasFlying = p.getAbilities().flying;
		p.getAbilities().mayfly = false;
		p.getAbilities().flying = false;
		p.onUpdateAbilities();
		if (wasFlying || !p.onGround()) p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, true));
		p.sendSystemMessage(Component.literal("Your flight has ended.").withStyle(ChatFormatting.YELLOW));
	}

	private static String human(long ms) {
		long s = ms / 1000;
		if (s < 120) return s + " seconds";
		long m = s / 60;
		if (m < 120) return m + " minutes";
		long h = m / 60;
		return h < 48 ? h + " hours" + (m % 60 > 0 ? " " + m % 60 + " min" : "") : h / 24 + " days";
	}

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-fly.json");
		until.clear();
		try {
			if (Files.exists(file)) {
				Map<String, Long> saved = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Long>>() { }.getType());
				if (saved != null) saved.forEach((k, v) -> until.put(UUID.fromString(k), v));
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read timed flights: {}", e.getMessage());
		}
	}

	private static void save() {
		if (file == null) return;
		Map<String, Long> out = new HashMap<>();
		until.forEach((k, v) -> out.put(k.toString(), v));
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save timed flights: {}", e.getMessage());
		}
	}
}
