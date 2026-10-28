package eu.explorerseden.nicecontrolcenter.tweaks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.Tweaks;

/**
 * Shared night (Tweaks.Sleep.shared): the listed worlds count their players together for the sleeping
 * percentage, so someone in bed in one of them helps skip the night in all, and they all wake together.
 * Vanilla counts each world on its own.
 */
public final class SleepPool {
	/** Implemented by the ServerLevel mixin. */
	public interface Access {
		void nicecontrolcenter$wakeUp();
	}

	private static boolean busy;

	private SleepPool() {
	}

	private static boolean on(ServerLevel level) {
		Tweaks.Sleep s = TweaksControl.settings().sleep;
		return s.shared && !eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.dungeonWorld(level) && s.worlds.contains(level.dimension().identifier().toString());
	}

	private static List<ServerLevel> others(ServerLevel level) {
		List<ServerLevel> out = new ArrayList<>();
		for (ServerLevel l : level.getServer().getAllLevels()) if (l != level && on(l)) out.add(l);
		return out;
	}

	/** The players who count for this world: its own, or everyone in the shared worlds. */
	public static List<ServerPlayer> players(ServerLevel level, List<ServerPlayer> own) {
		if (!on(level)) return own;
		List<ServerLevel> others = others(level);
		if (others.isEmpty()) return own;
		List<ServerPlayer> all = new ArrayList<>(own);
		for (ServerLevel l : others) all.addAll(l.players());
		return all;
	}

	/** Someone in a shared world got into or out of bed: the other shared worlds recount too. */
	public static void updated(ServerLevel level) {
		if (busy || !on(level)) return;
		busy = true;
		try {
			for (ServerLevel l : others(level)) l.updateSleepingPlayerList();
		} finally {
			busy = false;
		}
	}

	/** One shared world skipped the night: everyone in the others wakes too, so they don't skip it again. */
	public static void wokeUp(ServerLevel level) {
		if (busy || !on(level)) return;
		busy = true;
		try {
			for (ServerLevel l : others(level)) ((Access) l).nicecontrolcenter$wakeUp();
		} finally {
			busy = false;
		}
	}
}
