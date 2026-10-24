package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Claim blocks for playing: every minute each online player who wasn't AFK earns blocksPerHour / 60.
 * Moving, looking around or clicking counts as playing. Once a day (and at start) claims of players who
 * have been away longer than inactiveDays are removed, if that's set.
 */
public final class ClaimPlaytime {
	private static final int MINUTE = 1200;
	private static final int DAY = 20 * 60 * 60 * 24;

	private ClaimPlaytime() {
	}

	public static void joined(ServerPlayer player) {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled && !s.accrueWhileDisabled) return;
		ClaimStore.data().seen(player.getStringUUID(), player.getPlainTextName(), s, System.currentTimeMillis());
		ClaimStore.touched();
	}

	public static void left(ServerPlayer player) {
		Claims.PlayerBlocks p = ClaimStore.data().player(player.getStringUUID());
		if (p != null) {
			p.lastSeen = System.currentTimeMillis();
			ClaimStore.touched();
		}
	}

	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % MINUTE == 0) accrue(server, tick);
		if (tick % DAY == 200) expire();
	}

	private static void accrue(MinecraftServer server, int tick) {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled && !s.accrueWhileDisabled) return;
		long now = System.currentTimeMillis();
		boolean any = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean afk = s.afkMinutes > 0 && (eu.explorerseden.nicecontrolcenter.core.PlayerActivity.idleTicks(p) > s.afkMinutes * MINUTE
					|| eu.explorerseden.nicecontrolcenter.core.PlayerActivity.isAway(p));
			if (afk) continue;
			ClaimStore.data().accrue(p.getStringUUID(), p.getPlainTextName(), s, now);
			any = true;
		}
		if (any) ClaimStore.touched();
	}

	/**
	 * Claims of players away longer than inactiveDays go to their first co-owner who's still around (the
	 * old owner is no longer part of it); claims without one are removed.
	 */
	public static void expire() {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled || s.inactiveDays <= 0) return;
		Claims.Data data = ClaimStore.data();
		long now = System.currentTimeMillis();
		long cutoff = now - s.inactiveDays * 86_400_000L;
		List<Claim> gone = new ArrayList<>();
		int passed = 0;
		for (Claim c : List.copyOf(data.claims.values())) {
			if (c.parent != null || c.owner == null || !away(data, c.owner, cutoff)) continue;
			// Co-owners in the order they were added, who played within the time (one away too wouldn't keep it long).
			String heir = null;
			for (var m : c.members.entrySet()) {
				Claims.PlayerBlocks p = data.player(m.getKey());
				if (m.getValue().role == Claims.Role.MANAGE && p != null && p.lastSeen >= cutoff) {
					heir = m.getKey();
					break;
				}
			}
			if (heir == null) {
				gone.add(c);
				continue;
			}
			String oldName = c.ownerName;
			String heirName = c.members.get(heir).name != null ? c.members.get(heir).name : data.player(heir).name;
			// Like an admin hand-over: the claim doesn't need the new owner's claim blocks.
			data.transfer(c, heir, heirName, s, true, now);
			passed++;
			NiceControlCenter.LOGGER.info("Claim {} of {} (away more than {} days) went to its co-owner {}", c.displayName(), oldName, s.inactiveDays, heirName);
		}
		gone.forEach(data::delete);
		if (gone.isEmpty() && passed == 0) return;
		ClaimStore.changed();
		if (!gone.isEmpty()) NiceControlCenter.LOGGER.info("Removed {} claims of players away more than {} days", gone.size(), s.inactiveDays);
	}

	/** Has this player been away longer than the cutoff (players never seen don't count as away)? */
	private static boolean away(Claims.Data data, String uuid, long cutoff) {
		Claims.PlayerBlocks p = data.player(uuid);
		return p != null && p.lastSeen > 0 && p.lastSeen < cutoff;
	}
}
