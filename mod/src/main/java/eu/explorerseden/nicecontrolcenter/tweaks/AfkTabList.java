package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;
import eu.explorerseden.nicecontrolcenter.core.WorldClock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AFK tag in the tab list and the tab list's header and footer. The tag never touches scoreboard
 * teams (data packs like Fabled Roots use them): the player's tab list name is their team-styled name
 * (prefix, colour, suffix) with " AFK" after it, and back to the plain team-styled name when they return.
 */
public final class AfkTabList {
	private static final Set<UUID> afk = ConcurrentHashMap.newKeySet();
	private static boolean headerShown;

	private AfkTabList() {
	}

	/** For the tab list name mixin: the name with the tag while AFK, else null (vanilla's name). */
	public static Component displayName(ServerPlayer player) {
		if (!afk.contains(player.getUUID())) return null;
		Component name = PlayerTeam.formatNameForTeam(player.getTeam(), player.getName());
		return Component.empty().append(name).append(Component.literal(" " + TweaksControl.settings().afk.tag).withStyle(ChatFormatting.GRAY));
	}

	static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		Tweaks.Settings s = TweaksControl.settings();
		if (tick % 20 == 0) {
			int limit = s.afk.minutes * 1200;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				boolean now = s.afk.enabled && PlayerActivity.idleTicks(p) > limit;
				boolean changed = now ? afk.add(p.getUUID()) : afk.remove(p.getUUID());
				// While AFK, refresh now and then so a team change (another race) shows too.
				if (changed || now && tick % 1200 == 0) update(server, p);
			}
			afk.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		}
		if (tick % 100 == 0) {
			if (s.tabList.enabled) {
				headerShown = true;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					p.connection.send(new ClientboundTabListPacket(text(server, p, s.tabList.header), text(server, p, s.tabList.footer)));
				}
			} else if (headerShown) {
				headerShown = false;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) p.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
			}
		}
	}

	private static void update(MinecraftServer server, ServerPlayer player) {
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of(player)));
	}

	private static Component text(MinecraftServer server, ServerPlayer player, String template) {
		if (template.isBlank()) return Component.empty();
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		double tps = Math.min(server.tickRateManager().tickrate(), mspt > 0 ? 1000.0 / mspt : 20);
		WorldClock.Clock clock = WorldClock.current();
		String t = template.replace("\\n", "\n")
				.replace("{players}", String.valueOf(server.getPlayerCount()))
				.replace("{max}", String.valueOf(server.getMaxPlayers()))
				.replace("{tps}", String.format(Locale.ROOT, "%.1f", tps))
				.replace("{mspt}", String.format(Locale.ROOT, "%.1f", mspt))
				.replace("{ping}", String.valueOf(player.connection.latency()))
				.replace("{time}", clock == null || clock.time() == null ? "" : clock.time());
		return Component.literal(t);
	}
}
