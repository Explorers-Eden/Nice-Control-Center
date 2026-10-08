package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import eu.explorerseden.nicecontrolcenter.alert.AlertWatcher;
import eu.explorerseden.nicecontrolcenter.alert.BossBarMonitor;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.diagnosis.Advisor;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.data.Point;
import eu.explorerseden.nicecontrolcenter.mixin.LevelAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.ServerLevelAccessor;
import eu.explorerseden.nicecontrolcenter.record.Recorder;
import eu.explorerseden.nicecontrolcenter.sampler.StackSampler;
import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Once a second, on the server thread: turns the live counters into a {@link Breakdown} and stores it. */
public final class Snapshotter {
	private static final int HOTSPOT_CANDIDATES = 6;
	private static final Map<Object, String> typeIds = new IdentityHashMap<>();
	private static volatile StackSampler sampler;

	private Snapshotter() {
	}

	public static void sampler(StackSampler stackSampler) {
		sampler = stackSampler;
	}

	static void publish(MinecraftServer server, LiveBucket bucket) {
		if (server == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Breakdown b = new Breakdown();
		b.start = bucket.startMillis;
		b.end = now;
		b.seconds = 1;
		boolean paused = bucket.ticks == 0 && bucket.pausedTicks > 0;
		b.wallMs = paused ? 0 : Math.max(1, now - bucket.startMillis);
		b.pausedSeconds = paused ? 1 : 0;
		b.ticks = bucket.ticks;
		b.tickNsSum = bucket.tickNsSum;
		b.tickNsMax = bucket.tickNsMax;
		b.tickNsMin = bucket.ticks == 0 ? 0 : bucket.tickNsMin;
		System.arraycopy(bucket.msptHistogram, 0, b.msptHistogram, 0, b.msptHistogram.length);
		b.targetMspt = server.tickRateManager().millisecondsPerTick();
		b.spikes.addAll(bucket.spikes);

		bucket.forEachPhase((key, stat) -> {
			Breakdown.Row row = new Breakdown.Row();
			row.selfNs = stat.selfNs;
			b.phases.put(key.dimension() + "|" + key.phase().name(), row);
		});

		bucket.entities.forEach((type, stat) -> b.entities.put(typeId(type), timed(stat)));
		bucket.blockEntities.forEach((type, stat) -> {
			Breakdown.Row row = timed(stat);
			int ticking = bucket.ticks == 0 ? 0 : (int) Math.round(stat.calls / (double) bucket.ticks);
			row.countSum = ticking;
			row.countMax = ticking;
			b.blockEntities.put(typeId(type), row);
		});

		bucket.functions.forEach((id, stat) -> {
			Breakdown.FunctionRow row = new Breakdown.FunctionRow();
			row.selfNs = stat.selfNs;
			row.totalNs = stat.totalNs;
			row.calls = stat.calls;
			row.runs = stat.runs;
			row.pack = SourceIndex.packForFunction(server, id);
			row.calledFrom = stat.calledFrom == null ? null : stat.calledFrom.label;
			stat.lines.forEach((text, line) -> {
				Breakdown.LineRow lineRow = new Breakdown.LineRow();
				lineRow.selfNs = line.selfNs;
				lineRow.entries = line.entries;
				lineRow.runs = line.runs;
				row.lines.put(text.length() > 300 ? text.substring(0, 300) + "…" : text, lineRow);
			});
			b.functions.put(id.toString(), row);
		});

		countWorld(server, bucket, b);
		busiestBlocks(server, bucket, b);
		b.worldgen = WorldgenTracker.drain();

		SystemMetrics.Sample system = SystemMetrics.sample();
		b.cpuProcessSum = system.cpuProcess();
		b.cpuProcessMax = system.cpuProcess();
		b.cpuSystemSum = system.cpuSystem();
		b.heapUsedSum = system.heapUsed();
		b.heapUsedMax = system.heapUsed();
		b.heapMax = system.heapMax();
		b.heapLive = system.heapLive();
		b.heapLiveMax = system.heapLive();
		b.gcTimeMs = system.gcTimeMs();
		b.gcCount = system.gcCount();

		StackSampler s = sampler;
		if (s != null) {
			StackSampler.Drained drained = s.drain();
			b.samples = drained.samples();
			b.samplerIntervalMs = s.intervalMs();
			b.mods.putAll(drained.rows());
		}

		Point point = new Point(now, b.tps(1000.0 / Math.max(1e-3, b.targetMspt)), b.msptAvg(), b.tickNsMax / 1e6,
				system.heapUsed(), system.heapMax(), system.heapLive(), system.cpuProcess(), system.cpuSystem(),
				b.latest.entities, b.latest.blockEntities, b.latest.chunks, b.latest.players, system.gcTimeMs(), paused);
		History.add(b, point);
		AlertWatcher.onSecond(server, b);
		Advisor.maybeRun(server);
		BossBarMonitor.update(server);
		WorldClock.update(server);
		Recorder.onSecond(server, b, point);
	}

	private static final int BLOCK_CLUSTERS_PER_SECOND = 20;

	/** Keeps the busiest block clusters of this second and names their block. */
	private static void busiestBlocks(MinecraftServer server, LiveBucket bucket, Breakdown b) {
		List<long[]> all = new ArrayList<>();
		List<String> dims = new ArrayList<>(bucket.blockUpdates.keySet());
		for (int d = 0; d < dims.size(); d++) {
			int dimIndex = d;
			bucket.blockUpdates.get(dims.get(d)).long2IntEntrySet()
					.fastForEach(e -> all.add(new long[] {e.getLongKey(), e.getIntValue(), dimIndex}));
		}
		all.sort((x, y) -> Long.compare(y[1], x[1]));
		for (long[] entry : all.subList(0, Math.min(BLOCK_CLUSTERS_PER_SECOND, all.size()))) {
			String dimension = dims.get((int) entry[2]);
			long pos = bucket.blockUpdatePositions.get(dimension).get(entry[0]);
			Breakdown.BlockUpdates row = new Breakdown.BlockUpdates();
			row.dimension = dimension;
			row.x = BlockPos.getX(pos);
			row.y = BlockPos.getY(pos);
			row.z = BlockPos.getZ(pos);
			row.count = entry[1];
			row.seconds = 1;
			ServerLevel level = levelFor(server, dimension);
			if (level != null) {
				row.block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos.of(pos)).getBlock()).toString();
			}
			b.blockUpdates.put(dimension + "|" + BlockPos.getX(entry[0]) + "|" + BlockPos.getY(entry[0]) + "|" + BlockPos.getZ(entry[0]), row);
		}
	}

	private static ServerLevel levelFor(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (Tracker.dimension(level).equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	private static Breakdown.Row timed(Stat stat) {
		Breakdown.Row row = new Breakdown.Row();
		row.selfNs = stat.selfNs;
		row.totalNs = stat.totalNs;
		row.calls = stat.calls;
		return row;
	}

	/** Loaded counts, per-type entity counts and the busiest chunks. */
	private static void countWorld(MinecraftServer server, LiveBucket bucket, Breakdown b) {
		Breakdown.Counts counts = b.latest;
		counts.players = server.getPlayerCount();
		Map<String, Integer> entityCounts = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();

		for (ServerLevel level : server.getAllLevels()) {
			String dimension = Tracker.dimension(level);
			Long2IntOpenHashMap perChunk = new Long2IntOpenHashMap();
			for (Entity entity : level.getAllEntities()) {
				counts.entities++;
				entityCounts.merge(typeId(entity.getType()), 1, Integer::sum);
				perChunk.addTo(ChunkPos.pack(entity.getBlockX() >> 4, entity.getBlockZ() >> 4), 1);
			}
			List<TickingBlockEntity> tickers = ((LevelAccessor) level).nicecontrolcenter$blockEntityTickers();
			Long2IntOpenHashMap blockEntitiesPerChunk = new Long2IntOpenHashMap();
			for (TickingBlockEntity ticker : tickers) {
				BlockPos pos = ticker.getPos();
				if (pos == null) {
					// Placeholders without a position (e.g. Lithium's sleeping block entities).
					continue;
				}
				blockEntitiesPerChunk.addTo(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), 1);
			}
			counts.blockEntities += tickers.size();
			counts.chunks += level.getChunkSource().getLoadedChunksCount();
			counts.chunkTasks += level.getChunkSource().getPendingTasksCount();
			ServerLevelAccessor ticks = (ServerLevelAccessor) level;
			counts.blockTicks += ticks.nicecontrolcenter$blockTicks().count();
			counts.fluidTicks += ticks.nicecontrolcenter$fluidTicks().count();

			describePlayers(server, level, dimension, perChunk, blockEntitiesPerChunk, b);
			Long2LongOpenHashMap chunkNs = bucket.chunkNs.getOrDefault(dimension, new Long2LongOpenHashMap());
			pickCandidates(level, dimension, chunkNs, perChunk, blockEntitiesPerChunk, candidates);
		}
		b.max = copy(counts);
		creditNewChunks(b);

		entityCounts.forEach((type, count) -> {
			Breakdown.Row row = b.entities.computeIfAbsent(type, k -> new Breakdown.Row());
			row.countSum = count;
			row.countMax = count;
		});

		candidates.sort((x, y) -> Long.compare(y.score(), x.score()));
		for (Candidate candidate : candidates.subList(0, Math.min(HOTSPOT_CANDIDATES, candidates.size()))) {
			b.hotspots.put(candidate.hotspot.dimension + "|" + candidate.hotspot.x + "|" + candidate.hotspot.z, describe(candidate));
		}
	}

	/** Per player: where they are, ping, and how much is loaded around them. */
	private static void describePlayers(MinecraftServer server, ServerLevel level, String dimension, Long2IntOpenHashMap entities,
			Long2IntOpenHashMap blockEntities, Breakdown b) {
		int serverView = server.getPlayerList().getViewDistance();
		for (ServerPlayer player : level.players()) {
			Breakdown.PlayerRow row = new Breakdown.PlayerRow();
			row.name = player.getPlainTextName();
			row.dimension = dimension;
			row.x = player.getBlockX();
			row.y = player.getBlockY();
			row.z = player.getBlockZ();
			row.ping = player.connection.latency();
			row.viewDistance = Math.min(serverView, Math.max(2, player.requestedViewDistance()));
			row.seconds = 1;
			row.lastSeen = System.currentTimeMillis();
			int cx = row.x >> 4;
			int cz = row.z >> 4;
			int r = row.viewDistance;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					long chunk = ChunkPos.pack(cx + dx, cz + dz);
					row.entitiesNear += entities.get(chunk);
					row.blockEntitiesNear += blockEntities.get(chunk);
				}
			}
			b.players.put(player.getUUID().toString(), row);
		}
	}

	/** Gives each newly generated chunk to the nearest player in that dimension (within 32 chunks). */
	private static void creditNewChunks(Breakdown b) {
		for (WorldgenTracker.NewChunk chunk : WorldgenTracker.drainNewChunks()) {
			Breakdown.PlayerRow nearest = null;
			long best = 32L * 32L;
			for (Breakdown.PlayerRow row : b.players.values()) {
				if (!row.dimension.equals(chunk.dimension())) {
					continue;
				}
				long dx = (row.x >> 4) - chunk.x();
				long dz = (row.z >> 4) - chunk.z();
				long distance = dx * dx + dz * dz;
				if (distance <= best) {
					best = distance;
					nearest = row;
				}
			}
			if (nearest != null) {
				nearest.newChunks++;
			}
		}
	}

	private record Candidate(ServerLevel level, Breakdown.Hotspot hotspot) {
		long score() {
			// Rank by measured tick time, with loaded things as a tie breaker for idle chunks.
			return hotspot.ns + (hotspot.entities + hotspot.blockEntities) * 2_000L;
		}
	}

	private static void pickCandidates(ServerLevel level, String dimension, Long2LongOpenHashMap chunkNs,
			Long2IntOpenHashMap entities, Long2IntOpenHashMap blockEntities, List<Candidate> out) {
		Long2LongOpenHashMap score = new Long2LongOpenHashMap();
		chunkNs.long2LongEntrySet().fastForEach(e -> score.addTo(e.getLongKey(), e.getLongValue()));
		entities.long2IntEntrySet().fastForEach(e -> score.addTo(e.getLongKey(), e.getIntValue() * 2_000L));
		blockEntities.long2IntEntrySet().fastForEach(e -> score.addTo(e.getLongKey(), e.getIntValue() * 2_000L));
		List<long[]> sorted = new ArrayList<>();
		score.long2LongEntrySet().fastForEach(e -> sorted.add(new long[] {e.getLongKey(), e.getLongValue()}));
		sorted.sort((x, y) -> Long.compare(y[1], x[1]));
		for (long[] entry : sorted.subList(0, Math.min(HOTSPOT_CANDIDATES, sorted.size()))) {
			long chunk = entry[0];
			Breakdown.Hotspot hotspot = new Breakdown.Hotspot();
			hotspot.dimension = dimension;
			hotspot.x = ChunkPos.getX(chunk);
			hotspot.z = ChunkPos.getZ(chunk);
			hotspot.ns = chunkNs.get(chunk);
			hotspot.entities = entities.get(chunk);
			hotspot.blockEntities = blockEntities.get(chunk);
			out.add(new Candidate(level, hotspot));
		}
	}

	/** Finds what fills a hot chunk; only done for the few chunks that made the list. */
	private static Breakdown.Hotspot describe(Candidate candidate) {
		Breakdown.Hotspot hotspot = candidate.hotspot;
		Map<String, Integer> types = new HashMap<>();
		for (Entity entity : candidate.level.getAllEntities()) {
			if (entity.getBlockX() >> 4 == hotspot.x && entity.getBlockZ() >> 4 == hotspot.z) {
				types.merge(typeId(entity.getType()), 1, Integer::sum);
			}
		}
		for (TickingBlockEntity ticker : ((LevelAccessor) candidate.level).nicecontrolcenter$blockEntityTickers()) {
			BlockPos pos = ticker.getPos();
			if (pos != null && pos.getX() >> 4 == hotspot.x && pos.getZ() >> 4 == hotspot.z) {
				types.merge(ticker.getType(), 1, Integer::sum);
			}
		}
		types.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(top -> {
			hotspot.topType = top.getKey();
			hotspot.topTypeCount = top.getValue();
		});
		return hotspot;
	}

	private static Breakdown.Counts copy(Breakdown.Counts counts) {
		Breakdown.Counts c = new Breakdown.Counts();
		c.entities = counts.entities;
		c.blockEntities = counts.blockEntities;
		c.chunks = counts.chunks;
		c.players = counts.players;
		c.blockTicks = counts.blockTicks;
		c.fluidTicks = counts.fluidTicks;
		c.chunkTasks = counts.chunkTasks;
		return c;
	}

	static String typeId(Object type) {
		String id = typeIds.get(type);
		if (id == null) {
			Identifier key = null;
			if (type instanceof EntityType<?> entityType) {
				key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
			} else if (type instanceof BlockEntityType<?> blockEntityType) {
				key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
			}
			id = key == null ? String.valueOf(type) : key.toString();
			typeIds.put(type, id);
		}
		return id;
	}
}
