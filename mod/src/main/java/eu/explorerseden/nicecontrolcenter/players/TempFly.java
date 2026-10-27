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
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;

/**
 * Flight time as a credit, for inside claims only. Admins give it with /reward fly &lt;players&gt; &lt;time&gt; (time like /tick:
 * 100t, 30s, 1d = one in-game day; more time adds up) and take it with /reward fly &lt;players&gt; off. Players switch it on
 * with /fly inside a claim they own or are at least a Builder in; leaving such a claim switches it off. The time only runs down
 * while it's on and they're not AFK. When it's used up, switched off or they leave, they float down with Slow Falling.
 * Kept over logouts and restarts.
 */
public final class TempFly {
	public static final String PERMISSION = "nicecontrolcenter.fly";
	public static final String PERMISSION_USE = "nicecontrolcenter.fly.use";

	/** A player's flight credit. */
	static final class Grant {
		/** Flight time left, in milliseconds. */
		long left;
		/** Switched on with /fly. */
		boolean on;

		Grant(long left, boolean on) {
			this.left = left;
			this.on = on;
		}
	}

	private static final Map<UUID, Grant> grants = new HashMap<>();
	private static Path file;
	private static long lastTick;
	private static long lastSave;

	private TempFly() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
			dispatcher.register(Commands.literal("fly")
					.requires(Everyone.unless(PERMISSION_USE))
					.executes(ctx -> toggle(ctx.getSource())));
		});
		ServerLifecycleEvents.SERVER_STARTED.register(TempFly::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		// Every quarter second, so leaving a claim while flying is noticed quickly.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0) tick(server, server.getTickCount() % 20 == 0);
		});
	}

	/** Seconds of flight a player has left (for other features); 0 when none. */
	public static long secondsLeft(UUID uuid) {
		Grant g = grants.get(uuid);
		return g == null ? 0 : g.left / 1000;
	}

	// ---------------------------------------------------------------- commands

	/** Adds flight time (for /reward and rules); the player may be offline. Returns the time they have now. */
	public static long add(java.util.UUID uuid, ServerPlayer online, long ms) {
		Grant g = grants.computeIfAbsent(uuid, k -> new Grant(0, false));
		g.left += ms;
		if (online != null) {
			// Switched on right away when they stand in one of their claims; otherwise /fly there.
			boolean inside = inOwnClaim(online);
			if (inside) {
				g.on = true;
				allow(online);
			}
			online.sendSystemMessage(Component.literal("You got " + human(ms) + " of flight (" + human(g.left) + " in all) for your claims and the ones you're a Builder (or more) in. "
					+ (inside ? "It's on: double-tap jump to take off. " : "Type /fly inside one of them. ") + "/fly switches it on and off; the time only runs while it's on.")
					.withStyle(ChatFormatting.GOLD));
		}
		save();
		return g.left;
	}

	/** Takes all flight time away; false when they had none. */
	public static boolean take(java.util.UUID uuid, ServerPlayer online) {
		Grant g = grants.remove(uuid);
		if (g == null) return false;
		if (online != null && g.on) land(online, "Your flight time was taken away.");
		save();
		return true;
	}

	/** /fly: switch your flight on or off. */
	private static int toggle(CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Component.literal("Only players can fly."));
			return 0;
		}
		Grant g = grants.get(p.getUUID());
		if (g == null || g.left <= 0) {
			source.sendFailure(Component.literal("You have no flight time. It's given as a reward or by an admin."));
			return 0;
		}
		if (p.isCreative() || p.isSpectator()) {
			source.sendFailure(Component.literal("You can fly in this game mode anyway; your flight time stays as it is (" + human(g.left) + ")."));
			return 0;
		}
		if (!g.on && !inOwnClaim(p)) {
			source.sendFailure(Component.literal("You can only fly inside your claims and the ones you're a Builder (or more) in (" + human(g.left) + " of flight left)."));
			return 0;
		}
		g.on = !g.on;
		if (g.on) {
			allow(p);
			source.sendSuccess(() -> Component.literal("Flight on: " + human(g.left) + " left. Double-tap jump to take off; leaving the claim switches it off.")
					.withStyle(ChatFormatting.GOLD), false);
		} else {
			land(p, null);
			source.sendSuccess(() -> Component.literal("Flight off. " + human(g.left) + " saved for later.").withStyle(ChatFormatting.YELLOW), false);
		}
		save();
		return 1;
	}

	// ---------------------------------------------------------------- the clock

	private static void tick(MinecraftServer server, boolean second) {
		long now = System.currentTimeMillis();
		long elapsed = lastTick == 0 ? 0 : Math.min(5_000, now - lastTick);
		lastTick = now;
		if (grants.isEmpty()) return;
		boolean ended = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Grant g = grants.get(p.getUUID());
			if (g == null || !g.on) continue;
			if (p.isCreative() || p.isSpectator()) continue;
			if (!inOwnClaim(p)) {
				// Out of their claims: flight goes off (and the time stops) until they switch it on again inside one.
				g.on = false;
				land(p, "You left the claim, so your flight is off (" + human(g.left) + " left). /fly switches it on again inside your claims.");
				ended = true;
				continue;
			}
			// Game mode changes and respawns reset the abilities: give flight back.
			if (!p.getAbilities().mayfly) allow(p);
			// AFK players keep their time.
			if (PlayerActivity.isAfk(p)) continue;
			g.left -= elapsed;
			if (g.left <= 0) {
				grants.remove(p.getUUID());
				land(p, "Your flight time is used up.");
				ended = true;
				continue;
			}
			if (!second) continue;
			long s = g.left / 1000;
			if (s == 60 || s == 10 || (s <= 5 && s > 0)) p.sendOverlayMessage(Component.literal("Flight time left: " + s + " s").withStyle(ChatFormatting.YELLOW));
		}
		// The time left is saved now and then (and on stop), not every quarter second.
		if (ended || now - lastSave > 60_000) save();
	}

	private static boolean inOwnClaim(ServerPlayer p) {
		return Perks.inOwnClaim(p);
	}

	private static void allow(ServerPlayer p) {
		if (p.isCreative() || p.isSpectator()) return;
		p.getAbilities().mayfly = true;
		p.onUpdateAbilities();
	}

	private static void land(ServerPlayer p, String why) {
		if (p.isCreative() || p.isSpectator()) return;
		boolean wasFlying = p.getAbilities().flying;
		p.getAbilities().mayfly = false;
		p.getAbilities().flying = false;
		p.onUpdateAbilities();
		if (wasFlying || !p.onGround()) p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, true));
		if (why != null) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
	}

	private static String human(long ms) {
		long s = Math.max(0, ms) / 1000;
		if (s < 120) return s + " seconds";
		long m = s / 60;
		if (m < 120) return m + " minutes";
		long h = m / 60;
		return h < 48 ? h + " hours" + (m % 60 > 0 ? " " + m % 60 + " min" : "") : h / 24 + " days";
	}

	// ---------------------------------------------------------------- storage

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-fly.json");
		grants.clear();
		lastTick = 0;
		try {
			if (!Files.exists(file)) return;
			long now = System.currentTimeMillis();
			var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			for (var e : saved.entrySet()) {
				var v = e.getValue();
				Grant g;
				if (v.isJsonObject() && v.getAsJsonObject().has("left")) {
					var o = v.getAsJsonObject();
					g = new Grant(o.get("left").getAsLong(), o.has("on") && o.get("on").getAsBoolean());
				} else {
					// 1.39/1.40 kept the end time: what's left of it becomes the credit.
					long until = v.isJsonObject() ? v.getAsJsonObject().get("until").getAsLong() : v.getAsLong();
					g = new Grant(until - now, false);
				}
				if (g.left > 0) grants.put(UUID.fromString(e.getKey()), g);
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read flight times: {}", e.getMessage());
		}
	}

	private static void save() {
		lastSave = System.currentTimeMillis();
		if (file == null) return;
		Map<String, Map<String, Object>> out = new HashMap<>();
		grants.forEach((k, v) -> out.put(k.toString(), Map.of("left", v.left, "on", v.on)));
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save flight times: {}", e.getMessage());
		}
	}
}
