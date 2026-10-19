package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.mixin.ServerChunkCacheAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.TicketStorageAccessor;

/**
 * Warm teleport targets. A teleport to a chunk that isn't loaded loads (or generates) it on the main thread,
 * which is most of the lag when players use hubs, homes or spawn. Places teleported to a few times a day
 * keep their 3×3 chunks loaded with a ticket of their own: loaded only, nothing in them ticks. They show
 * under Loaded chunks as "warm"; releasing one there forgets it. Server thread only.
 */
public final class WarmTargets {
	/** Teleports to the same chunk within a day before it's kept warm. */
	private static final int VISITS = 3;
	private static final long DAY = 24 * 60 * 60 * 1000L;
	/** Warm places at most; the one visited longest ago goes first. */
	private static final int MAX_WARM = 24;
	/** Places remembered while they're counted. */
	private static final int MAX_SEEN = 400;
	/** Shorter teleports land where the player already had chunks loaded. */
	private static final int MIN_CHUNKS = 8;

	private record Place(ResourceKey<Level> dimension, long chunk) {
	}

	private static final class Seen {
		final ArrayDeque<Long> visits = new ArrayDeque<>();
		long last;
		boolean warm;
	}

	private static TicketType ticket;
	private static final Map<Place, Seen> seen = new HashMap<>();
	private static int warmCount;

	private WarmTargets() {
	}

	/** At mod start, while registries are still open. */
	public static void register() {
		ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(NiceControlCenter.MOD_ID, "warm"),
				new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
	}

	/** Before a player is teleported to an absolute spot. */
	public static void teleporting(ServerPlayer player, ServerLevel to, double x, double z) {
		if (ticket == null || !PerfControl.settings().smooth.teleports) return;
		int cx = ((int) Math.floor(x)) >> 4;
		int cz = ((int) Math.floor(z)) >> 4;
		if (to == player.level() && Math.abs(cx - (player.getBlockX() >> 4)) < MIN_CHUNKS && Math.abs(cz - (player.getBlockZ() >> 4)) < MIN_CHUNKS) return;
		visit(to, cx, cz);
	}

	/** A teleport to this chunk; the third within a day keeps it warm. */
	static void visit(ServerLevel to, int cx, int cz) {
		long now = System.currentTimeMillis();
		Place place = new Place(to.dimension(), ChunkPos.pack(cx, cz));
		Seen s = seen.get(place);
		if (s == null) {
			if (seen.size() >= MAX_SEEN) forgetOldestCold();
			s = new Seen();
			seen.put(place, s);
		}
		s.last = now;
		s.visits.addLast(now);
		while (!s.visits.isEmpty() && s.visits.peekFirst() < now - DAY) s.visits.pollFirst();
		if (!s.warm && s.visits.size() >= VISITS) {
			if (warmCount >= MAX_WARM) coolOldest(to.getServer());
			to.getChunkSource().addTicketWithRadius(ticket, new ChunkPos(cx, cz), 1);
			s.warm = true;
			warmCount++;
		}
	}

	/** Once a minute: places not visited for a day cool down; ones released under Loaded chunks are forgotten. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 1200 != 0 || seen.isEmpty()) return;
		boolean on = PerfControl.settings().smooth.teleports;
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<Place, Seen>> it = seen.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Place, Seen> e = it.next();
			Seen s = e.getValue();
			ServerLevel level = server.getLevel(e.getKey().dimension());
			if (s.warm && (level == null || !hasTicket(level, e.getKey().chunk()))) {
				// Released by hand.
				s.warm = false;
				warmCount--;
				it.remove();
				continue;
			}
			if (s.warm && (!on || s.last < now - DAY)) {
				cool(level, e.getKey(), s);
			}
			if (!s.warm && s.last < now - DAY) it.remove();
		}
	}

	private static boolean hasTicket(ServerLevel level, long chunk) {
		List<Ticket> tickets = ((TicketStorageAccessor) ((ServerChunkCacheAccessor) level.getChunkSource()).nicecontrolcenter$ticketStorage())
				.nicecontrolcenter$tickets().get(chunk);
		if (tickets == null) return false;
		for (Ticket t : tickets) {
			if (t.getType() == ticket) return true;
		}
		return false;
	}

	private static void cool(ServerLevel level, Place place, Seen s) {
		if (level != null) level.getChunkSource().removeTicketWithRadius(ticket, ChunkPos.unpack(place.chunk()), 1);
		s.warm = false;
		s.visits.clear();
		warmCount--;
	}

	private static void coolOldest(MinecraftServer server) {
		Map.Entry<Place, Seen> oldest = null;
		for (Map.Entry<Place, Seen> e : seen.entrySet()) {
			if (e.getValue().warm && (oldest == null || e.getValue().last < oldest.getValue().last)) oldest = e;
		}
		if (oldest != null) cool(server.getLevel(oldest.getKey().dimension()), oldest.getKey(), oldest.getValue());
	}

	private static void forgetOldestCold() {
		Place oldest = null;
		long last = Long.MAX_VALUE;
		for (Map.Entry<Place, Seen> e : seen.entrySet()) {
			if (!e.getValue().warm && e.getValue().last < last) {
				last = e.getValue().last;
				oldest = e.getKey();
			}
		}
		if (oldest != null) seen.remove(oldest);
	}

	/** How many places are kept warm right now. */
	public static int warm() {
		return warmCount;
	}
}
