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

/**
 * /ncc fly &lt;players&gt; &lt;time&gt; [everywhere|claims]: creative flight in survival for a while (time like /tick: 100t,
 * 30s, 1d = one in-game day), either everywhere or only inside claims the player owns or is a member of (outside them
 * it pauses). /ncc fly &lt;players&gt; off ends it. Survives logging out and restarts; when it runs out (or the player
 * leaves their claims) they float down with Slow Falling.
 */
public final class TempFly {
	public static final String PERMISSION = "nicecontrolcenter.fly";
	/** A player's flight: when it ends (epoch millis) and whether only in their claims. */
	static final class Grant {
		long until;
		boolean claims;
		/** Claims only: currently outside them, flight paused (not saved). */
		transient boolean paused;

		Grant(long until, boolean claims) {
			this.until = until;
			this.claims = claims;
		}
	}

	private static final Map<UUID, Grant> until = new HashMap<>();
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
								.then(Commands.argument("time", TimeArgument.time(1))
										.executes(ctx -> grant(ctx, EntityArgument.getPlayers(ctx, "players"), ctx.getArgument("time", Integer.class), false))
										.then(Commands.literal("everywhere").executes(ctx -> grant(ctx, EntityArgument.getPlayers(ctx, "players"), ctx.getArgument("time", Integer.class), false)))
										.then(Commands.literal("claims").executes(ctx -> grant(ctx, EntityArgument.getPlayers(ctx, "players"), ctx.getArgument("time", Integer.class), true)))))));
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(TempFly::load);
		// Every quarter second, so leaving a claim while flying is noticed quickly.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0 && !until.isEmpty()) tick(server, server.getTickCount() % 20 == 0);
		});
	}

	private static int grant(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, int ticks, boolean claims) {
		long ms = ticks * 50L;
		long end = System.currentTimeMillis() + ms;
		for (ServerPlayer p : players) {
			Grant g = new Grant(end, claims);
			until.put(p.getUUID(), g);
			if (!claims || inOwnClaim(p)) allow(p);
			else g.paused = true;
			p.sendSystemMessage(Component.literal(claims
					? "You can fly in your claims (and ones you're a member of) for " + human(ms) + ". Double-tap jump to take off."
					: "You can fly for " + human(ms) + ". Double-tap jump to take off.").withStyle(ChatFormatting.AQUA));
		}
		save();
		ctx.getSource().sendSuccess(() -> Component.literal("Flight for " + (players.size() == 1 ? players.iterator().next().getGameProfile().name() : players.size() + " players")
				+ " for " + human(ms) + " (" + ticks + " ticks)" + (claims ? ", only in their claims." : ", everywhere.")), true);
		return players.size();
	}

	/** Inside a claim (or subclaim) the player owns or has any role in. */
	private static boolean inOwnClaim(ServerPlayer p) {
		var claim = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.claimAt(p.level(), p.blockPosition());
		return claim != null && eu.explorerseden.nicecontrolcenter.claims.ClaimStore.data().roleOf(claim, p.getUUID().toString()) != null;
	}

	private static int off(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
		int n = 0;
		for (ServerPlayer p : players) {
			if (until.remove(p.getUUID()) == null) continue;
			land(p, "Your flight has ended.");
			n++;
		}
		save();
		int count = n;
		ctx.getSource().sendSuccess(() -> Component.literal(count == 0 ? "Nobody of them had timed flight." : "Flight ended for " + count + " player" + (count == 1 ? "" : "s") + "."), true);
		return n;
	}

	private static void tick(MinecraftServer server, boolean second) {
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Grant g = until.get(p.getUUID());
			if (g == null) continue;
			long left = g.until - now;
			if (left <= 0) {
				until.remove(p.getUUID());
				if (!g.paused) land(p, "Your flight has ended.");
				else p.sendSystemMessage(Component.literal("Your flight has ended.").withStyle(ChatFormatting.YELLOW));
				changed = true;
				continue;
			}
			if (g.claims) {
				boolean inside = inOwnClaim(p);
				if (!inside && !g.paused) {
					g.paused = true;
					land(p, "You left your claims: no flying here. It comes back inside them.");
					continue;
				}
				if (inside && g.paused) {
					g.paused = false;
					allow(p);
					p.sendOverlayMessage(Component.literal("You can fly here").withStyle(ChatFormatting.AQUA));
				}
				if (g.paused) continue;
			}
			// Game mode changes and respawns reset the abilities: give flight back.
			if (!p.getAbilities().mayfly) allow(p);
			if (!second) continue;
			long s = left / 1000;
			if (s == 60 || s == 10 || (s <= 5 && s > 0)) p.sendOverlayMessage(Component.literal("Flight ends in " + s + " s").withStyle(ChatFormatting.YELLOW));
		}
		if (changed) save();
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
		p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
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
				// {uuid: {until, claims}}; files from 1.39.0 hold just the end time.
				var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				for (var e : saved.entrySet()) {
					var v = e.getValue();
					Grant g = v.isJsonObject() ? new Grant(v.getAsJsonObject().get("until").getAsLong(), v.getAsJsonObject().has("claims") && v.getAsJsonObject().get("claims").getAsBoolean())
							: new Grant(v.getAsLong(), false);
					until.put(UUID.fromString(e.getKey()), g);
				}
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read timed flights: {}", e.getMessage());
		}
	}

	private static void save() {
		if (file == null) return;
		Map<String, Map<String, Object>> out = new HashMap<>();
		until.forEach((k, v) -> out.put(k.toString(), Map.of("until", v.until, "claims", v.claims)));
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save timed flights: {}", e.getMessage());
		}
	}
}
