package eu.explorerseden.nicecontrolcenter.core;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * Vanilla works out a player's whole locator bar on every movement packet: it checks the player against every
 * entity that sends a waypoint (its range attribute, whether it's hidden, the distance), up to 20 times a second
 * per moving player. Mobs given a transmit range by a data pack make that hundreds of checks per packet.
 *
 * <p>With this on, it's done at most every {@link #EVERY_TICKS} ticks per player, and right away after a jump
 * (teleport) or a dimension change. Nothing visible changes: the client turns the markers with the camera itself,
 * and a moving mob or player still updates its own marker for everyone at once. A player who stops gets the last
 * update with the client's next position packet (at least once a second). Server thread.
 */
public final class LocatorBar {
	public static final int EVERY_TICKS = 5;
	/** Moved further than this since the last update: a teleport, updated at once. */
	private static final double JUMP = 16;

	/** Per player: server tick, x, z of the last update, and the level it was in. */
	private static final Map<ServerPlayer, Object[]> last = new WeakHashMap<>();

	private LocatorBar() {
	}

	public static boolean due(ServerPlayer player) {
		if (!PerfControl.settings().smooth.locatorBar) return true;
		int tick = player.level().getServer().getTickCount();
		Object[] l = last.get(player);
		if (l != null && l[3] == player.level() && tick - (int) l[0] < EVERY_TICKS) {
			double dx = player.getX() - (double) l[1];
			double dz = player.getZ() - (double) l[2];
			if (dx * dx + dz * dz < JUMP * JUMP) return false;
		}
		last.put(player, new Object[] { tick, player.getX(), player.getZ(), player.level() });
		return true;
	}
}
