package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim blocks for playing: every minute each online player who wasn't AFK earns blocksPerHour / 60.
 * Moving, looking around or clicking counts as playing. Once a day (and at start) claims of players who
 * have been away longer than inactiveDays are removed, if that's set.
 */
public final class ClaimPlaytime {
	private static final int MINUTE = 1200;
	private static final int DAY = 20 * 60 * 60 * 24;

	private record Seen(double x, double y, double z, float yRot, float xRot) {
	}

	private static final Map<UUID, Seen> last = new HashMap<>();
	/** Player → server tick they last did something. */
	private static final Map<UUID, Integer> active = new HashMap<>();

	private ClaimPlaytime() {
	}

	public static void joined(ServerPlayer player) {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled && !s.accrueWhileDisabled) return;
		ClaimStore.data().seen(player.getStringUUID(), player.getPlainTextName(), s, System.currentTimeMillis());
		active.put(player.getUUID(), player.level().getServer().getTickCount());
		ClaimStore.touched();
	}

	public static void left(ServerPlayer player) {
		last.remove(player.getUUID());
		active.remove(player.getUUID());
		Claims.PlayerBlocks p = ClaimStore.data().player(player.getStringUUID());
		if (p != null) {
			p.lastSeen = System.currentTimeMillis();
			ClaimStore.touched();
		}
	}

	/** Someone clicked or typed: they're not AFK. */
	public static void acted(ServerPlayer player) {
		active.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Seen now = new Seen(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
				Seen before = last.put(p.getUUID(), now);
				if (before == null || before.yRot != now.yRot || before.xRot != now.xRot
						|| Math.abs(before.x - now.x) + Math.abs(before.z - now.z) > 0.5 && p.getVehicle() == null) {
					active.put(p.getUUID(), tick);
				}
			}
		}
		if (tick % MINUTE == 0) accrue(server, tick);
		if (tick % DAY == 200) expire();
	}

	private static void accrue(MinecraftServer server, int tick) {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled && !s.accrueWhileDisabled) return;
		long now = System.currentTimeMillis();
		boolean any = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Integer at = active.get(p.getUUID());
			boolean afk = s.afkMinutes > 0 && (at == null || tick - at > s.afkMinutes * MINUTE);
			if (afk) continue;
			ClaimStore.data().accrue(p.getStringUUID(), p.getPlainTextName(), s, now);
			any = true;
		}
		if (any) ClaimStore.touched();
	}

	/** Removes the claims of players away longer than inactiveDays. */
	public static void expire() {
		Claims.Settings s = ClaimStore.settings();
		if (!s.enabled || s.inactiveDays <= 0) return;
		Claims.Data data = ClaimStore.data();
		long cutoff = System.currentTimeMillis() - s.inactiveDays * 86_400_000L;
		List<Claim> gone = new ArrayList<>();
		for (Claim c : data.claims.values()) {
			if (c.parent != null || c.owner == null) continue;
			Claims.PlayerBlocks p = data.player(c.owner);
			if (p != null && p.lastSeen > 0 && p.lastSeen < cutoff) gone.add(c);
		}
		if (gone.isEmpty()) return;
		gone.forEach(data::delete);
		ClaimStore.changed();
		NiceControlCenter.LOGGER.info("Removed {} claims of players away more than {} days", gone.size(), s.inactiveDays);
	}
}
