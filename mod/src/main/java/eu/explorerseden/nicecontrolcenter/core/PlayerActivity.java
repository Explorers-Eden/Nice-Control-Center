package eu.explorerseden.nicecontrolcenter.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each online player last did something (moved, looked around), for AFK checks: claim blocks are
 * only earned while active, and the tab list can tag AFK players. Checked once a second.
 */
public final class PlayerActivity {
	private record Seen(double x, double y, double z, float yRot, float xRot) {
	}

	private static final Map<UUID, Seen> last = new ConcurrentHashMap<>();
	/** Player → server tick they last did something. */
	private static final Map<UUID, Integer> active = new ConcurrentHashMap<>();

	private PlayerActivity() {
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PlayerActivity::tick);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, s, server) -> acted(h.player));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
			last.remove(h.player.getUUID());
			active.remove(h.player.getUUID());
		});
	}

	public static void acted(ServerPlayer player) {
		active.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	/** Ticks since the player last did something (0 when unknown). */
	public static int idleTicks(ServerPlayer player) {
		Integer at = active.get(player.getUUID());
		return at == null ? 0 : player.level().getServer().getTickCount() - at;
	}

	private static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 20 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Seen now = new Seen(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
			Seen before = last.put(p.getUUID(), now);
			// Looking around counts; moving only on foot (minecarts and AFK pools move you without you).
			if (before == null || before.yRot != now.yRot || before.xRot != now.xRot
					|| Math.abs(before.x - now.x) + Math.abs(before.z - now.z) > 0.5 && p.getVehicle() == null) {
				active.put(p.getUUID(), tick);
			}
		}
	}
}
