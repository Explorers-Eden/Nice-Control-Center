package eu.explorerseden.nicecontrolcenter.core;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each online player last did something (moved, looked around), for AFK checks: claim blocks are
 * only earned while active, and the tab list can tag AFK players. Checked once a second. Players can also
 * mark themselves AFK with /afk; moving or looking around ends it.
 */
public final class PlayerActivity {
	private record Seen(double x, double y, double z, float yRot, float xRot) {
	}

	private static final Map<UUID, Seen> last = new ConcurrentHashMap<>();
	/** Player → server tick they last did something. */
	private static final Map<UUID, Integer> active = new ConcurrentHashMap<>();
	/** Players who said they're AFK with /afk. */
	private static final Set<UUID> away = ConcurrentHashMap.newKeySet();
	public static final String PERMISSION_AFK = "nicecontrolcenter.afk";

	private PlayerActivity() {
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PlayerActivity::tick);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, s, server) -> acted(h.player));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
			last.remove(h.player.getUUID());
			active.remove(h.player.getUUID());
			away.remove(h.player.getUUID());
		});
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
				Commands.literal("afk").requires(eu.explorerseden.nicecontrolcenter.command.Everyone.unless(PERMISSION_AFK)).executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					if (away.remove(player.getUUID())) {
						acted(player);
						player.sendSystemMessage(Component.literal("You're back.").withStyle(ChatFormatting.GRAY));
					} else {
						away.add(player.getUUID());
						// Measured from here, so a step taken just before the command doesn't end it at once.
						last.put(player.getUUID(), seen(player));
						player.sendSystemMessage(Component.literal("You're AFK now. Moving or looking around ends it.").withStyle(ChatFormatting.GRAY));
					}
					return 1;
				})));
	}

	/** Marked AFK with /afk. */
	public static boolean isAway(ServerPlayer player) {
		return away.contains(player.getUUID());
	}

	private static Seen seen(ServerPlayer p) {
		return new Seen(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	public static void acted(ServerPlayer player) {
		active.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	/** AFK: marked with /afk, or did nothing for the gameplay tweaks' AFK minutes (whether or not the tab list shows the tag). */
	public static boolean isAfk(ServerPlayer player) {
		return isAway(player) || idleTicks(player) > eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl.settings().afk.minutes * 1200;
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
			Seen now = seen(p);
			Seen before = last.put(p.getUUID(), now);
			// Looking around counts; moving only on foot (minecarts and AFK pools move you without you).
			if (before == null || before.yRot != now.yRot || before.xRot != now.xRot
					|| Math.abs(before.x - now.x) + Math.abs(before.z - now.z) > 0.5 && p.getVehicle() == null) {
				active.put(p.getUUID(), tick);
				if (before != null && away.remove(p.getUUID())) p.sendSystemMessage(Component.literal("You're back.").withStyle(ChatFormatting.GRAY));
			}
		}
	}
}
