package eu.explorerseden.nicecontrolcenter.alert;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.diagnosis.Diagnoser;
import eu.explorerseden.nicecontrolcenter.diagnosis.Views;

/**
 * In-game live monitor without a client mod: a boss bar with TPS and MSPT for players who turned
 * it on with /ncc bossbar. The choice is remembered across restarts.
 */
public final class BossBarMonitor {
	private static final int WINDOW_SECONDS = 5;
	private static final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
	private static ServerBossEvent bar;

	private BossBarMonitor() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("nicecontrolcenter-bossbar.json");
	}

	public static void load() {
		viewers.clear();
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Set<UUID> saved = Json.GSON.fromJson(reader, new TypeToken<Set<UUID>>() { }.getType());
			if (saved != null) {
				viewers.addAll(saved);
			}
		} catch (IOException | JsonParseException e) {
			NiceControlCenter.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private static void save() {
		try (Writer writer = Files.newBufferedWriter(file())) {
			Json.GSON.toJson(viewers, writer);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save {}", file(), e);
		}
	}

	/** Turns the bar on or off for a player; returns whether it is now on. */
	public static boolean toggle(ServerPlayer player) {
		boolean on;
		if (viewers.remove(player.getUUID())) {
			if (bar != null) {
				bar.removePlayer(player);
			}
			on = false;
		} else {
			viewers.add(player.getUUID());
			on = true;
		}
		save();
		return on;
	}

	public static void stop() {
		if (bar != null) {
			bar.removeAllPlayers();
			bar = null;
		}
	}

	/** Called once per second on the server thread. */
	public static void update(MinecraftServer server) {
		if (viewers.isEmpty()) {
			if (bar != null) {
				bar.removeAllPlayers();
			}
			return;
		}
		if (bar == null) {
			bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Nice Control Center"), BossEvent.BossBarColor.GREEN,
					BossEvent.BossBarOverlay.PROGRESS);
		}
		Breakdown recent = History.lastSeconds(WINDOW_SECONDS);
		Views.Kpi kpi = Views.kpi(recent);
		double budget = kpi.budget();
		String text;
		if (recent.ticks == 0) {
			text = recent.pausedSeconds > 0 ? "Nice Control Center · server paused (nobody online)" : "Nice Control Center · waiting for data";
		} else {
			text = String.format(Locale.ROOT, "TPS %.1f · MSPT %.1f med / %.1f 95%% / %.0f max", kpi.tps(), kpi.msptMedian(), kpi.msptP95(), kpi.msptMax());
			if (kpi.msptAvg() > budget) {
				Diagnoser.Finding cause = Diagnoser.diagnose(recent).findings().stream().filter(f -> f.ms() > 0).findFirst().orElse(null);
				if (cause != null) {
					text += " · mainly " + cause.title();
				}
			}
		}
		bar.setName(Component.literal(text));
		bar.setProgress((float) Math.max(0, Math.min(1, kpi.msptAvg() / budget)));
		bar.setColor(kpi.msptAvg() <= budget * 0.7 ? BossEvent.BossBarColor.GREEN
				: kpi.msptAvg() <= budget ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);

		for (UUID id : viewers) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && !bar.getPlayers().contains(player)) {
				bar.addPlayer(player);
			}
		}
		for (ServerPlayer player : java.util.List.copyOf(bar.getPlayers())) {
			if (!viewers.contains(player.getUUID()) || player.hasDisconnected()) {
				bar.removePlayer(player);
			}
		}
	}
}
