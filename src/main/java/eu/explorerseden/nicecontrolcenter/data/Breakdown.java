package eu.explorerseden.nicecontrolcenter.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.explorerseden.nicecontrolcenter.core.TickHistogram;

/**
 * Everything measured over a stretch of time: one second, a 1/5/15 minute window, or a whole recording.
 * Breakdowns merge by adding up, so windows and reports are just merges of per-second breakdowns.
 * Plain fields so Gson can write and read them directly.
 */
public final class Breakdown {
	public long start;
	public long end;
	public int seconds;
	/** Wall-clock time the server was ticking; a "second" can stretch when a tick is very slow. */
	public long wallMs;
	/** Seconds in which the server paused itself because nobody was online. */
	public int pausedSeconds;

	// Ticks
	public long ticks;
	public long tickNsSum;
	public long tickNsMax;
	/** Fastest tick; 0 when no ticks were measured. */
	public long tickNsMin;
	public int[] msptHistogram = new int[TickHistogram.BINS];
	public double targetMspt = 50;

	// System
	public double cpuProcessSum;
	public double cpuSystemSum;
	public double cpuProcessMax;
	public long heapUsedSum;
	public long heapUsedMax;
	public long heapMax;
	/** Memory in use after garbage collection: latest value and highest value in the window. */
	public long heapLive;
	public long heapLiveMax;
	public long gcTimeMs;
	public long gcCount;

	public Counts latest = new Counts();
	public Counts max = new Counts();

	/** Keyed "dimension|PHASE". */
	public Map<String, Row> phases = new HashMap<>();
	public Map<String, FunctionRow> functions = new HashMap<>();
	public Map<String, Row> entities = new HashMap<>();
	public Map<String, Row> blockEntities = new HashMap<>();
	public Map<String, ModRow> mods = new HashMap<>();
	public long samples;
	public int samplerIntervalMs;
	/** Keyed "dimension|x|z". */
	public Map<String, Hotspot> hotspots = new HashMap<>();
	public List<Spike> spikes = new ArrayList<>();
	/** Busiest 4×4×4 block clusters for scheduled ticks and block events, keyed "dimension|cx|cy|cz". */
	public Map<String, BlockUpdates> blockUpdates = new HashMap<>();
	public Worldgen worldgen = new Worldgen();
	/** Online players, keyed by UUID: latest position/ping, new chunks summed. */
	public Map<String, PlayerRow> players = new HashMap<>();

	public static final class Counts {
		public int entities;
		public int blockEntities;
		public int chunks;
		public int players;
		public int blockTicks;
		public int fluidTicks;
		public int chunkTasks;

		void max(Counts other) {
			entities = Math.max(entities, other.entities);
			blockEntities = Math.max(blockEntities, other.blockEntities);
			chunks = Math.max(chunks, other.chunks);
			players = Math.max(players, other.players);
			blockTicks = Math.max(blockTicks, other.blockTicks);
			fluidTicks = Math.max(fluidTicks, other.fluidTicks);
			chunkTasks = Math.max(chunkTasks, other.chunkTasks);
		}
	}

	public static class Row {
		public long selfNs;
		public long totalNs;
		public long calls;
		/** Sum of the per-second loaded count; divide by seconds for the average. */
		public long countSum;
		public int countMax;

		void add(Row other) {
			selfNs += other.selfNs;
			totalNs += other.totalNs;
			calls += other.calls;
			countSum += other.countSum;
			countMax = Math.max(countMax, other.countMax);
		}
	}

	public static final class FunctionRow extends Row {
		/** Pack id that provides the function file, or null for direct commands. */
		public String pack;
		public String calledFrom;
		/** Times the function was called; {@link #calls} counts the command queue entries it ran. */
		public long runs;
		public Map<String, LineRow> lines = new HashMap<>();

		void addFunction(FunctionRow other) {
			add(other);
			runs += other.runs;
			if (pack == null) {
				pack = other.pack;
			}
			if (calledFrom == null) {
				calledFrom = other.calledFrom;
			}
			other.lines.forEach((text, line) -> lines.computeIfAbsent(text, k -> new LineRow()).add(line));
		}
	}

	public static final class LineRow {
		public long selfNs;
		public long entries;
		public long runs;

		void add(LineRow other) {
			selfNs += other.selfNs;
			entries += other.entries;
			runs += other.runs;
		}
	}

	public static final class ModRow {
		public String name;
		/** Samples where this mod's code was running itself. */
		public long self;
		/** Samples where vanilla code was running on behalf of this mod (the mod is the nearest caller). */
		public long caused;
		/** Samples where this mod's code was anywhere on the stack. */
		public long total;
		/** Hottest own methods, "Class.method" or "Target.method (mixin)". */
		public Map<String, Long> methods = new HashMap<>();

		void add(ModRow other) {
			if (name == null) {
				name = other.name;
			}
			self += other.self;
			caused += other.caused;
			total += other.total;
			other.methods.forEach((method, count) -> methods.merge(method, count, Long::sum));
		}
	}

	/** A summed timing: total nanoseconds and how often it ran. */
	public static final class Timing {
		public long ns;
		public long count;

		void add(Timing other) {
			ns += other.ns;
			count += other.count;
		}
	}

	/** World generation on worker threads: new chunks and time per stage, placed feature and structure. */
	public static final class Worldgen {
		public long chunks;
		public Map<String, Timing> stages = new HashMap<>();
		public Map<String, Timing> features = new HashMap<>();
		public Map<String, Timing> structures = new HashMap<>();

		void add(Worldgen other) {
			chunks += other.chunks;
			other.stages.forEach((k, v) -> stages.computeIfAbsent(k, x -> new Timing()).add(v));
			other.features.forEach((k, v) -> features.computeIfAbsent(k, x -> new Timing()).add(v));
			other.structures.forEach((k, v) -> structures.computeIfAbsent(k, x -> new Timing()).add(v));
		}
	}

	public static final class PlayerRow {
		public String name;
		public String dimension;
		public int x;
		public int y;
		public int z;
		public int ping;
		public int viewDistance;
		public int entitiesNear;
		public int blockEntitiesNear;
		/** New chunks generated closest to this player. */
		public long newChunks;
		public int seconds;
		/** When this player was last seen online. */
		public long lastSeen;

		void add(PlayerRow newer) {
			lastSeen = Math.max(lastSeen, newer.lastSeen);
			newChunks += newer.newChunks;
			seconds += newer.seconds;
			name = newer.name;
			dimension = newer.dimension;
			x = newer.x;
			y = newer.y;
			z = newer.z;
			ping = newer.ping;
			viewDistance = newer.viewDistance;
			entitiesNear = newer.entitiesNear;
			blockEntitiesNear = newer.blockEntitiesNear;
		}
	}

	public static final class BlockUpdates {
		public String dimension;
		public int x;
		public int y;
		public int z;
		public String block;
		public long count;
		/** Seconds in which this cluster was among the busiest. */
		public int seconds;

		void add(BlockUpdates other) {
			count += other.count;
			seconds += other.seconds;
			if (block == null) {
				block = other.block;
			}
		}
	}

	public static final class Hotspot {
		public String dimension;
		public int x;
		public int z;
		public long ns;
		public int entities;
		public int blockEntities;
		public String topType;
		public int topTypeCount;

		void add(Hotspot other) {
			ns += other.ns;
			if (other.entities + other.blockEntities >= entities + blockEntities) {
				entities = other.entities;
				blockEntities = other.blockEntities;
				topType = other.topType;
				topTypeCount = other.topTypeCount;
			}
		}
	}

	/** One slow tick and the phases that made it slow. */
	public static final class Spike {
		public long time;
		public double ms;
		public List<Share> phases = new ArrayList<>();
		/** Functions, entity types and block entity types that took the most time in this tick. */
		public List<Share> top = new ArrayList<>();
	}

	public static final class Share {
		public String name;
		public double ms;

		public Share() {
		}

		public Share(String name, double ms) {
			this.name = name;
			this.ms = ms;
		}
	}

	public void merge(Breakdown other) {
		start = start == 0 ? other.start : Math.min(start, other.start);
		end = Math.max(end, other.end);
		seconds += other.seconds;
		wallMs += other.wallMs;
		pausedSeconds += other.pausedSeconds;
		ticks += other.ticks;
		tickNsSum += other.tickNsSum;
		tickNsMax = Math.max(tickNsMax, other.tickNsMax);
		if (other.ticks > 0) {
			tickNsMin = ticks == other.ticks ? other.tickNsMin : Math.min(tickNsMin, other.tickNsMin);
		}
		for (int i = 0; i < msptHistogram.length && i < other.msptHistogram.length; i++) {
			msptHistogram[i] += other.msptHistogram[i];
		}
		targetMspt = other.targetMspt;

		cpuProcessSum += other.cpuProcessSum;
		cpuSystemSum += other.cpuSystemSum;
		cpuProcessMax = Math.max(cpuProcessMax, other.cpuProcessMax);
		heapUsedSum += other.heapUsedSum;
		heapUsedMax = Math.max(heapUsedMax, other.heapUsedMax);
		heapMax = Math.max(heapMax, other.heapMax);
		heapLiveMax = Math.max(heapLiveMax, other.heapLiveMax);
		if (other.end >= end) {
			heapLive = other.heapLive;
		}
		gcTimeMs += other.gcTimeMs;
		gcCount += other.gcCount;

		if (other.end >= end) {
			latest = other.latest;
		}
		max.max(other.max);

		other.phases.forEach((k, v) -> phases.computeIfAbsent(k, x -> new Row()).add(v));
		other.functions.forEach((k, v) -> functions.computeIfAbsent(k, x -> new FunctionRow()).addFunction(v));
		other.entities.forEach((k, v) -> entities.computeIfAbsent(k, x -> new Row()).add(v));
		other.blockEntities.forEach((k, v) -> blockEntities.computeIfAbsent(k, x -> new Row()).add(v));
		other.mods.forEach((k, v) -> mods.computeIfAbsent(k, x -> new ModRow()).add(v));
		samples += other.samples;
		samplerIntervalMs = Math.max(samplerIntervalMs, other.samplerIntervalMs);
		other.hotspots.forEach((k, v) -> hotspots.merge(k, v, (a, b) -> {
			Hotspot merged = copy(a);
			merged.add(b);
			return merged;
		}));
		other.blockUpdates.forEach((k, v) -> blockUpdates.computeIfAbsent(k, x -> {
			BlockUpdates copy = new BlockUpdates();
			copy.dimension = v.dimension;
			copy.x = v.x;
			copy.y = v.y;
			copy.z = v.z;
			return copy;
		}).add(v));
		if (other.players != null) {
			if (players == null) {
				players = new HashMap<>();
			}
			other.players.forEach((k, v) -> players.computeIfAbsent(k, x -> new PlayerRow()).add(v));
		}
		if (other.worldgen != null) {
			if (worldgen == null) {
				worldgen = new Worldgen();
			}
			worldgen.add(other.worldgen);
		}
		spikes.addAll(other.spikes);
		if (spikes.size() > 50) {
			spikes.sort((a, b) -> Double.compare(b.ms, a.ms));
			spikes = new ArrayList<>(spikes.subList(0, 50));
		}
	}

	private static Hotspot copy(Hotspot h) {
		Hotspot c = new Hotspot();
		c.dimension = h.dimension;
		c.x = h.x;
		c.z = h.z;
		c.ns = h.ns;
		c.entities = h.entities;
		c.blockEntities = h.blockEntities;
		c.topType = h.topType;
		c.topTypeCount = h.topTypeCount;
		return c;
	}

	// ── Derived values ──────────────────────────────────────────────────────

	public double tps(double targetTps) {
		return wallMs == 0 ? targetTps : Math.min(targetTps, ticks * 1000.0 / wallMs);
	}

	public double msptAvg() {
		return ticks == 0 ? 0 : tickNsSum / 1e6 / ticks;
	}

	/** Milliseconds per tick spent on something measured in total nanoseconds. */
	public double perTick(long ns) {
		return ticks == 0 ? 0 : ns / 1e6 / ticks;
	}

	public double cpuProcessAvg() {
		return seconds == 0 ? 0 : cpuProcessSum / seconds;
	}

	public double cpuSystemAvg() {
		return seconds == 0 ? 0 : cpuSystemSum / seconds;
	}

	public long heapUsedAvg() {
		return seconds == 0 ? 0 : heapUsedSum / seconds;
	}
}
