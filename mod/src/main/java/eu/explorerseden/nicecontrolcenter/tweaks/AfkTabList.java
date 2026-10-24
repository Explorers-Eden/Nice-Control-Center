package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;
import eu.explorerseden.nicecontrolcenter.core.WorldClock;
import eu.explorerseden.nicecontrolcenter.data.History;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AFK tag and each player's dimension in the tab list, and the tab list's header and footer. The tag never touches scoreboard
 * teams (data packs like Fabled Roots use them): the player's tab list name is their team-styled name
 * (prefix, colour, suffix) with " AFK" after it, and back to the plain team-styled name when they return.
 */
public final class AfkTabList {
	private static final Set<UUID> afk = ConcurrentHashMap.newKeySet();
	/** The dimension each player was last shown in (for noticing a change). */
	private static final Map<UUID, String> shownIn = new ConcurrentHashMap<>();
	private static boolean headerShown;
	/** The dimension settings last applied, so a change in the panel updates every name. */
	private static String dimensionKey = "";

	private AfkTabList() {
	}

	/** For the tab list name mixin: the dimension, the name and the AFK tag, else null (vanilla's name). */
	public static Component displayName(ServerPlayer player) {
		Tweaks.TabList t = TweaksControl.settings().tabList;
		boolean isAfk = afk.contains(player.getUUID());
		if (!isAfk && !t.showDimension) return null;
		MutableComponent out = Component.empty();
		if (t.showDimension) {
			Tweaks.Dimension d = Tweaks.dimension(t, dimension(player));
			out.append(Component.literal(d.name + " ").withStyle(st -> st.withColor(TextColor.fromRgb(Integer.parseInt(d.color.substring(1), 16)))));
		}
		out.append(PlayerTeam.formatNameForTeam(player.getTeam(), player.getName()));
		if (isAfk) out.append(Component.literal(" " + TweaksControl.settings().afk.tag).withStyle(ChatFormatting.GRAY));
		return out;
	}

	private static String dimension(ServerPlayer player) {
		return player.level().dimension().identifier().toString();
	}

	static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		Tweaks.Settings s = TweaksControl.settings();
		if (tick % 20 == 0) {
			int limit = s.afk.minutes * 1200;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				boolean now = s.afk.enabled && (PlayerActivity.idleTicks(p) > limit || PlayerActivity.isAway(p));
				boolean changed = now ? afk.add(p.getUUID()) : afk.remove(p.getUUID());
				// While AFK, refresh now and then so a team change (another race) shows too.
				if (changed || now && tick % 1200 == 0) update(server, p);
			}
			afk.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
			shownIn.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
			// Dimensions: a player who changed dimension, or new names and colours from the panel.
			String key = s.tabList.showDimension ? Json.GSON.toJson(s.tabList.dimensions) : "";
			boolean all = !key.equals(dimensionKey);
			dimensionKey = key;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				String dim = dimension(p);
				boolean moved = !dim.equals(shownIn.put(p.getUUID(), dim));
				if (all || moved && s.tabList.showDimension) update(server, p);
			}
		}
		if (tick % 20 == 0) {
			if (s.tabList.enabled) {
				headerShown = true;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					String header = s.tabList.header, footer = s.tabList.footer;
					// An empty line between them and the player list.
					if (s.tabList.gap && !header.isBlank()) header = header + "\n";
					if (s.tabList.gap && !footer.isBlank()) footer = "\n" + footer;
					p.connection.send(new ClientboundTabListPacket(text(server, p, header), text(server, p, footer)));
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
		// Ticks that really ran in the last seconds (1000 / mspt only drops once the average tick is over budget).
		double target = server.tickRateManager().tickrate();
		History.Recent recent = History.recent(5);
		double tps = recent.wallMs() > 0 ? Math.min(target, recent.ticks() * 1000.0 / recent.wallMs()) : Math.min(target, mspt > 0 ? 1000.0 / mspt : 20);
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
