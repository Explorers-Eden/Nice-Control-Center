package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayDeque;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.mixin.PlayerListInvoker;

/**
 * Autosave without the spike. Vanilla writes every changed chunk and every player file in the one tick the
 * autosave comes (every 5 minutes, often 150–250 ms on a busy server). With this, that tick only notes
 * what needs saving; chunks then go a few per tick (see SmoothSaveMixin, at most 1.5 ms a tick) and players
 * one per tick. What's still waiting when the next autosave comes is saved right away, as vanilla would.
 * save-all, /stop and the backup's save-all flush stay exactly vanilla. Server thread only.
 */
public final class SmoothSave {
	private static boolean autosaving;
	private static final ArrayDeque<UUID> players = new ArrayDeque<>();

	private SmoothSave() {
	}

	public static boolean on() {
		return PerfControl.settings().smooth.saving;
	}

	/** Between the start and end of MinecraftServer.autoSave. */
	public static boolean autosaving() {
		return autosaving;
	}

	public static void beginAutosave() {
		autosaving = on();
	}

	public static void endAutosave() {
		autosaving = false;
	}

	/** Instead of PlayerList.saveAll during an autosave: leftovers now, then everyone online one per tick. */
	public static void queuePlayers(MinecraftServer server) {
		saveWaitingPlayers(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getUUID());
	}

	/** Everyone is being saved right now (save-all, stop). */
	public static void clearPlayers() {
		players.clear();
	}

	public static void tick(MinecraftServer server) {
		UUID next = players.poll();
		if (next == null) return;
		// Players who left meanwhile were saved when they left.
		ServerPlayer p = server.getPlayerList().getPlayer(next);
		if (p != null) ((PlayerListInvoker) server.getPlayerList()).nicecontrolcenter$save(p);
	}

	private static void saveWaitingPlayers(MinecraftServer server) {
		while (!players.isEmpty()) tick(server);
	}
}
