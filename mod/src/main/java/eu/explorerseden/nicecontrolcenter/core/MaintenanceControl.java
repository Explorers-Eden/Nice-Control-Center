package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.Maintenance;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.mixin.StatusIconAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies config/nicecontrolcenter-maintenance.json (see {@link Maintenance}): swaps the MOTD and icon,
 * refuses everyone but operators and staff at login, and kicks the rest after a countdown in chat.
 */
public final class MaintenanceControl {
	private static volatile Maintenance.Settings settings = new Maintenance.Settings();
	private static long modified = -1;
	private static boolean applied;
	private static ServerStatus.Favicon normalIcon;
	/** Server tick at which the remaining players are kicked; -1 = no countdown running. */
	private static long kickAt = -1;

	private MaintenanceControl() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Maintenance.FILE);
	}

	public static boolean active() {
		return settings.enabled;
	}

	public static void reset() {
		modified = -1;
		applied = false;
		normalIcon = null;
		kickAt = -1;
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (modified < 0 || tick % 40 == 0) reload(server);
		if (kickAt < 0) return;
		long left = kickAt - tick;
		if (left <= 0) {
			kickAt = -1;
			kickOthers(server);
		} else if (left % 20 == 0) {
			long seconds = left / 20;
			if (seconds == 60 || seconds == 30 || seconds == 10 || seconds <= 5) {
				Component warning = Component.literal("Maintenance: the server closes for players in " + seconds + (seconds == 1 ? " second." : " seconds."))
						.withStyle(ChatFormatting.GOLD);
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					if (!allowed(server, p.nameAndId())) p.sendSystemMessage(warning);
				}
			}
		}
	}

	private static void reload(MinecraftServer server) {
		Path file = file();
		long now;
		try {
			now = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		Maintenance.Settings next = Maintenance.read(file);
		settings = next;
		if (next.enabled) {
			apply(server);
			// Countdown only for a fresh switch, not when the server starts in maintenance.
			if (!first && server.getPlayerList().getPlayers().stream().anyMatch(p -> !allowed(server, p.nameAndId()))) {
				if (next.countdown <= 0) kickOthers(server);
				else kickAt = server.getTickCount() + next.countdown * 20L;
			}
		} else {
			kickAt = -1;
			undo(server);
		}
	}

	private static void apply(MinecraftServer server) {
		if (!applied) {
			normalIcon = ((StatusIconAccessor) server).nicecontrolcenter$statusIcon();
			applied = true;
			NiceControlCenter.LOGGER.info("Maintenance mode is on: only operators and staff can join");
		}
		Path icon = server.getServerDirectory().resolve(Maintenance.ICON);
		try {
			if (Files.isRegularFile(icon)) ((StatusIconAccessor) server).nicecontrolcenter$setStatusIcon(new ServerStatus.Favicon(Files.readAllBytes(icon)));
		} catch (java.io.IOException e) {
			NiceControlCenter.LOGGER.warn("Couldn't read {}: {}", Maintenance.ICON, e.toString());
		}
		server.invalidateStatus();
	}

	private static void undo(MinecraftServer server) {
		if (!applied) return;
		applied = false;
		((StatusIconAccessor) server).nicecontrolcenter$setStatusIcon(normalIcon);
		server.invalidateStatus();
		NiceControlCenter.LOGGER.info("Maintenance mode is off");
	}

	private static boolean allowed(MinecraftServer server, NameAndId player) {
		return server.getPlayerList().isOp(player) || settings.isStaff(player.name());
	}

	private static void kickOthers(MinecraftServer server) {
		Component message = Component.literal(settings.kickMessage);
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!allowed(server, p.nameAndId())) p.connection.disconnect(message);
		}
	}

	/** The server list's MOTD while in maintenance, or null for the normal one. */
	public static String motd() {
		Maintenance.Settings s = settings;
		return s.enabled && applied ? s.motd.replace("\\n", "\n") : null;
	}

	/** From the login check: the refusal while in maintenance, or null. */
	public static Component refuse(MinecraftServer server, NameAndId player) {
		if (!settings.enabled || allowed(server, player)) return null;
		return Component.literal(settings.kickMessage);
	}
}
