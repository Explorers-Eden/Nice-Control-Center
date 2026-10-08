package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import net.minecraft.resources.Identifier;

/**
 * Raw counters for the current second. Filled on the server thread only, then handed to
 * {@link Snapshotter} which turns it into an immutable {@link eu.explorerseden.nicecontrolcenter.data.Breakdown}.
 */
public final class LiveBucket {
	/** Map key for commands that run outside any function (chat, console, command blocks). */
	public static final Identifier DIRECT_COMMANDS = Identifier.fromNamespaceAndPath("nicecontrolcenter", "direct_commands");

	public final long startMillis = System.currentTimeMillis();
	public int ticks;
	/** Ticks skipped because the server paused itself while empty. */
	public int pausedTicks;
	public long tickNsSum;
	public long tickNsMax;
	public long tickNsMin = Long.MAX_VALUE;
	public final int[] msptHistogram = new int[TickHistogram.BINS];

	public final Map<Object, Stat> entities = new IdentityHashMap<>();
	public final Map<Object, Stat> blockEntities = new IdentityHashMap<>();
	public final Map<Identifier, FunctionStat> functions = new HashMap<>();
	/** Self time attributed to each phase (time in nested non-phase frames counts toward the enclosing phase), by {@link PhaseKey#index()}. */
	private Stat[] phaseStats = new Stat[64];
	private PhaseKey[] phaseKeys = new PhaseKey[64];
	/** Entity and block entity tick time per chunk, keyed by dimension. */
	public final Map<String, Long2LongOpenHashMap> chunkNs = new HashMap<>();
	public final List<Breakdown.Spike> spikes = new ArrayList<>();
	/** Block updates per 4×4×4 cluster, keyed by dimension; plus one real position per cluster. */
	public final Map<String, Long2IntOpenHashMap> blockUpdates = new HashMap<>();
	public final Map<String, Long2LongOpenHashMap> blockUpdatePositions = new HashMap<>();

	public Stat entity(Object type) {
		return entities.computeIfAbsent(type, k -> new Stat(Stat.ENTITY, k));
	}

	public Stat blockEntity(Object type) {
		return blockEntities.computeIfAbsent(type, k -> new Stat(Stat.BLOCK_ENTITY, k));
	}

	public FunctionStat function(Identifier id) {
		Identifier key = id == null ? DIRECT_COMMANDS : id;
		FunctionStat stat = functions.get(key);
		if (stat == null) {
			stat = new FunctionStat(key);
			functions.put(key, stat);
		}
		return stat;
	}

	public Stat phase(PhaseKey key) {
		int index = key.index();
		if (index >= phaseStats.length) {
			int size = Math.max(index + 1, phaseStats.length * 2);
			phaseStats = java.util.Arrays.copyOf(phaseStats, size);
			phaseKeys = java.util.Arrays.copyOf(phaseKeys, size);
		}
		Stat stat = phaseStats[index];
		if (stat == null) {
			stat = new Stat();
			phaseStats[index] = stat;
			phaseKeys[index] = key;
		}
		return stat;
	}

	public void forEachPhase(java.util.function.BiConsumer<PhaseKey, Stat> action) {
		for (int i = 0; i < phaseStats.length; i++) {
			if (phaseStats[i] != null) {
				action.accept(phaseKeys[i], phaseStats[i]);
			}
		}
	}

	public void addBlockUpdate(String dimension, long pos) {
		long cluster = net.minecraft.core.BlockPos.asLong(net.minecraft.core.BlockPos.getX(pos) >> 2,
				net.minecraft.core.BlockPos.getY(pos) >> 2, net.minecraft.core.BlockPos.getZ(pos) >> 2);
		blockUpdates.computeIfAbsent(dimension, k -> new Long2IntOpenHashMap()).addTo(cluster, 1);
		blockUpdatePositions.computeIfAbsent(dimension, k -> new Long2LongOpenHashMap()).put(cluster, pos);
	}

	public void addChunkTime(String dimension, long chunk, long ns) {
		chunkNs.computeIfAbsent(dimension, k -> new Long2LongOpenHashMap()).addTo(chunk, ns);
	}
}
