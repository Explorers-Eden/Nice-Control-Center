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
 * <p>
 * Players in such a claim, and Nice Mob Manager's village name markers standing in one, carry the tag
 * {@value #TAG}: the pack leaves out its own village greeting for them, so the claim's title isn't covered by it.
 */
public final class ClaimTitles {
	private static final long AGAIN_MS = 60_000;
	public static final String TAG = "ncc.claim_title";
	/** Nice Mob Manager's armor stand that carries a village's name. */
	private static final String VILLAGE = "mob_manager.village.name";
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
			handler.player.removeTag(TAG);
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
			boolean titled = c != null && c.announce;
			if (titled) p.addTag(TAG);
			else p.removeTag(TAG);
			if (server.getTickCount() % 100 == 0) villages(p);
			if (id == null || id.equals(was) || !c.announce) continue;
			Map<String, Long> times = greeted.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
			Long last = times.get(id);
			if (last != null && now - last < AGAIN_MS) continue;
			times.put(id, now);
			times.values().removeIf(t -> now - t > AGAIN_MS);
			greet(p, c);
		}
	}

	/** Village name markers around the player: tagged while they stand in a claim with a title. */
	private static void villages(ServerPlayer p) {
		var box = p.getBoundingBox().inflate(160);
		for (var stand : p.level().getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, box, e -> e.entityTags().contains(VILLAGE))) {
			Claim at = ClaimStore.claimAt(stand.level(), stand.blockPosition());
			if (at != null && at.announce) stand.addTag(TAG);
			else stand.removeTag(TAG);
		}
	}

	private static void greet(ServerPlayer p, Claim c) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(c.description).withColor(0xE6E6E6)));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(c.displayName()).withColor(c.color)));
	}
}
