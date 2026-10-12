package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.LevelChunk;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.mixin.ServerChunkCacheAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.TicketStorageAccessor;

/**
 * Chunks kept loaded with no player near: /forceload, ender pearls in flight or stasis, portals, the
 * dragon fight. Each entry is where a ticket sits, with why, whether it also ticks, and what's there.
 * A ticket can be released; a pearl or portal that's used again loads its chunk again.
 */
public final class LoadedChunks {
	private static final int MAX = 200;

	private LoadedChunks() {
	}

	private static String name(TicketType type) {
		Identifier key = BuiltInRegistries.TICKET_TYPE.getKey(type);
		return key == null ? "unknown" : key.getPath();
	}

	/** Player tickets are the normal way chunks load, and "unknown" ones only live while a chunk loads; everything else is listed. */
	private static boolean listed(TicketType type) {
		return type != TicketType.PLAYER_LOADING && type != TicketType.PLAYER_SIMULATION && type != TicketType.UNKNOWN;
	}

	private static TicketStorage storage(ServerLevel level) {
		return ((ServerChunkCacheAccessor) level.getChunkSource()).nicecontrolcenter$ticketStorage();
	}

	public static Map<String, Object> list(MinecraftServer server) {
		List<Map<String, Object>> rows = new ArrayList<>();
		int simulated = 0;
		int view = server.getPlayerList().getViewDistance();
		for (ServerLevel level : server.getAllLevels()) {
			Long2ObjectOpenHashMap<List<Ticket>> tickets = ((TicketStorageAccessor) storage(level)).nicecontrolcenter$tickets();
			if (tickets.isEmpty()) continue;
			// Players' chunks, to leave out anything a player is near anyway.
			List<ServerPlayer> players = level.players();
			Long2IntOpenHashMap entities = null;
			for (var entry : tickets.long2ObjectEntrySet()) {
				long pos = entry.getLongKey();
				int x = ChunkPos.getX(pos);
				int z = ChunkPos.getZ(pos);
				TreeSet<String> reasons = new TreeSet<>();
				boolean ticks = false;
				for (Ticket t : entry.getValue()) {
					if (!listed(t.getType())) continue;
					reasons.add(name(t.getType()));
					ticks |= t.getType().doesSimulate();
				}
				if (reasons.isEmpty() || nearPlayer(players, x, z, view)) continue;
				if (entities == null) entities = entityCounts(level);
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("dimension", level.dimension().identifier().toString());
				row.put("x", x);
				row.put("z", z);
				row.put("reasons", new ArrayList<>(reasons));
				row.put("ticks", ticks);
				int around = 0;
				int blockEntities = 0;
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						around += entities.get(ChunkPos.pack(x + dx, z + dz));
						LevelChunk chunk = level.getChunkSource().getChunkNow(x + dx, z + dz);
						if (chunk != null) blockEntities += chunk.getBlockEntities().size();
					}
				}
				row.put("entities", around);
				row.put("blockEntities", blockEntities);
				rows.add(row);
				if (ticks) simulated++;
			}
		}
		rows.sort(Comparator.comparing((Map<String, Object> r) -> !(boolean) r.get("ticks"))
				.thenComparing(r -> -((int) r.get("entities") + (int) r.get("blockEntities"))));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("count", rows.size());
		out.put("ticking", simulated);
		out.put("chunks", rows.size() > MAX ? rows.subList(0, MAX) : rows);
		return out;
	}

	private static boolean nearPlayer(List<ServerPlayer> players, int x, int z, int view) {
		for (ServerPlayer p : players) {
			if (Math.abs((p.getBlockX() >> 4) - x) <= view && Math.abs((p.getBlockZ() >> 4) - z) <= view) return true;
		}
		return false;
	}

	private static Long2IntOpenHashMap entityCounts(ServerLevel level) {
		Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
		for (Entity e : level.getAllEntities()) counts.addTo(ChunkPos.pack(e.getBlockX() >> 4, e.getBlockZ() >> 4), 1);
		return counts;
	}

	/** Lets go of a chunk's tickets other than players' (/forceload is switched off for it). Returns an error, or null. */
	public static String release(MinecraftServer server, String dimension, int x, int z) {
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.dimension().identifier().toString().equals(dimension)) continue;
			TicketStorage storage = storage(level);
			long pos = ChunkPos.pack(x, z);
			List<Ticket> list = ((TicketStorageAccessor) storage).nicecontrolcenter$tickets().get(pos);
			if (list == null || list.isEmpty()) return "That chunk has no tickets any more.";
			if (level.getForceLoadedChunks().contains(pos)) level.setChunkForced(x, z, false);
			Map<String, Integer> released = new HashMap<>();
			for (Ticket t : new ArrayList<>(list)) {
				if (!listed(t.getType()) || t.getType() == TicketType.FORCED) continue;
				storage.removeTicket(pos, t);
				released.merge(name(t.getType()), 1, Integer::sum);
			}
			NiceControlCenter.LOGGER.info("Released chunk {}, {} in {} ({})", x, z, dimension, released.isEmpty() ? "forceload" : released);
			return null;
		}
		return "Unknown dimension.";
	}
}
