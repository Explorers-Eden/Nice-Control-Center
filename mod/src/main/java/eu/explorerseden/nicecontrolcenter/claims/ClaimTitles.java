package eu.explorerseden.nicecontrolcenter.claims;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.Claims.Claim;

/**
 * Claims with the title switched on greet players walking in: the name as a title in the claim's colour, the
 * description as subtitle. Checked twice a second; the same claim greets a player again only after a minute, so
 * walking along its edge doesn't flash it over and over.
 */
public final class ClaimTitles {
	private static final long AGAIN_MS = 60_000;
	/** player → the claim they're in (innermost), or absent. */
	private static final Map<UUID, String> inside = new HashMap<>();
	/** player → claim → when it last greeted them. */
	private static final Map<UUID, Map<String, Long>> greeted = new HashMap<>();

	private ClaimTitles() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) tick(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			inside.remove(handler.player.getUUID());
			greeted.remove(handler.player.getUUID());
		});
	}

	private static void tick(MinecraftServer server) {
		if (!ClaimStore.enabled()) return;
		long now = System.currentTimeMillis();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Claim c = p.isSpectator() ? null : ClaimStore.claimAt(p.level(), p.blockPosition());
			String id = c == null ? null : c.id;
			String was = id == null ? inside.remove(p.getUUID()) : inside.put(p.getUUID(), id);
			if (id == null || id.equals(was) || !c.announce) continue;
			Map<String, Long> times = greeted.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
			Long last = times.get(id);
			if (last != null && now - last < AGAIN_MS) continue;
			times.put(id, now);
			times.values().removeIf(t -> now - t > AGAIN_MS);
			greet(p, c);
		}
	}

	private static void greet(ServerPlayer p, Claim c) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(c.description).withColor(0xE6E6E6)));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(c.displayName()).withColor(c.color)));
	}
}
