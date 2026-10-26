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
 * Flight time as a credit, for inside claims only. Admins give it with /ncc fly &lt;players&gt; &lt;time&gt; (time like /tick:
 * 100t, 30s, 1d = one in-game day; more time adds up) and take it with /ncc fly &lt;players&gt; off. Players switch it on
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
			for (String root : new String[] { "ncc", "nicecontrolcenter" }) {
				dispatcher.register(Commands.literal(root).then(Commands.literal("fly")
						.requires(Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS))
						.then(Commands.argument("players", EntityArgument.players())
								.then(Commands.literal("off").executes(ctx -> off(ctx, EntityArgument.getPlayers(ctx, "players"))))
								.then(Commands.argument("time", TimeArgument.time(1))
										.executes(ctx -> grant(ctx, EntityArgument.getPlayers(ctx, "players"), ctx.getArgument("time", Integer.class)))))));
			}
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

	private static int grant(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, int ticks) {
		long ms = ticks * 50L;
		for (ServerPlayer p : players) {
			Grant g = grants.computeIfAbsent(p.getUUID(), k -> new Grant(0, false));
			g.left += ms;
			// Switched on right away when they stand in one of their claims; otherwise /fly there.
			boolean inside = inOwnClaim(p);
			if (inside) {
				g.on = true;
				allow(p);
			}
			p.sendSystemMessage(Component.literal("You got " + human(ms) + " of flight (" + human(g.left) + " in all) for your claims and the ones you're a Builder (or more) in. "
					+ (inside ? "It's on: double-tap jump to take off. " : "Type /fly inside one of them. ") + "/fly switches it on and off; the time only runs while it's on.")
					.withStyle(ChatFormatting.AQUA));
		}
		save();
		ctx.getSource().sendSuccess(() -> Component.literal("Gave " + human(ms) + " (" + ticks + " ticks) of flight in their claims to "
				+ (players.size() == 1 ? players.iterator().next().getGameProfile().name() : players.size() + " players") + "."), true);
		return players.size();
	}

	private static int off(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
		int n = 0;
		for (ServerPlayer p : players) {
			Grant g = grants.remove(p.getUUID());
			if (g == null) continue;
			if (g.on) land(p, "Your flight time was taken away.");
			n++;
		}
		save();
		int count = n;
		ctx.getSource().sendSuccess(() -> Component.literal(count == 0 ? "None of them had flight time." : "Took the flight time of " + count + " player" + (count == 1 ? "" : "s") + "."), true);
		return n;
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
					.withStyle(ChatFormatting.AQUA), false);
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

	/** Inside a claim (or subclaim) the player owns or is at least a Builder in. */
	private static boolean inOwnClaim(ServerPlayer p) {
		var claim = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.claimAt(p.level(), p.blockPosition());
		if (claim == null) return false;
		var role = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.data().roleOf(claim, p.getUUID().toString());
		return role != null && role.includes(eu.explorerseden.nicecontrolcenter.Claims.Role.BUILD);
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
