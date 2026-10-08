package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.explorerseden.nicecontrolcenter.core.Phase;
import eu.explorerseden.nicecontrolcenter.core.SourceIndex;
import eu.explorerseden.nicecontrolcenter.core.TickHistogram;
import eu.explorerseden.nicecontrolcenter.core.Tracker;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;

/**
 * Turns a {@link Breakdown} into sorted, labelled tables for the dashboard, the report and chat.
 * All times are milliseconds per tick, all shares are percent of the tick budget (50 ms at 20 TPS).
 */
public final class Views {
	private static final int TOP = 25;

	private Views() {
	}

	// ── Table rows ──────────────────────────────────────────────────────────

	public record Kpi(double tps, double targetTps, double msptAvg, double msptMin, double msptMedian, double msptP95, double msptMax, double budget,
			double cpuProcess, double cpuSystem, double cpuProcessMax, long heapUsed, long heapUsedMax, long heapMax, long heapLive, long heapLiveMax,
			double gcPercent, long gcCount, Breakdown.Counts counts, Breakdown.Counts countsMax, int seconds, long samples,
			int cores) {
	}

	public record PhaseView(String phase, String label, String description, double ms, double percent, List<DimensionShare> dimensions) {
	}

	public record DimensionShare(String dimension, double ms) {
	}

	public record LineView(String text, double ms, double runsPerTick, double perRun) {
	}

	public record FunctionView(String id, double ms, double percent, double runsPerTick, String calledFrom, List<LineView> lines) {
	}

	public record PackView(String id, String name, String kind, double ms, double percent, List<FunctionView> functions) {
	}

	public record MethodView(String name, double percent) {
	}

	public record ModView(String id, String name, double ms, double percent, double selfPercent, double causedPercent,
			double stackPercent, List<MethodView> methods) {
	}

	public record TypeView(String id, String source, double ms, double percent, double averageCount, int maxCount, double microsEach) {
	}

	/** One world generation item; ms is worker time per generated chunk. */
	public record TimingView(String id, String source, double msPerChunk, double percent, long count) {
	}

	public record WorldgenView(long chunks, double chunksPerMinute, double msPerChunk, List<TimingView> stages,
			List<TimingView> structures, List<TimingView> features, List<TimingView> sources) {
	}

	public record PlayerView(String name, String dimension, int x, int y, int z, int ping, int viewDistance, int entitiesNear,
			int blockEntitiesNear, double newChunksPerMinute, double newChunksPercent, boolean online) {
	}

	public record BlockUpdateView(String dimension, int x, int y, int z, String block, double perSecond, double activePercent) {
	}

	public record HotspotView(String dimension, int chunkX, int chunkZ, int blockX, int blockZ, double ms, int entities,
			int blockEntities, String topType, int topTypeCount) {
	}

	// ── Builders ────────────────────────────────────────────────────────────

	public static Kpi kpi(Breakdown b) {
		double targetTps = 1000.0 / Math.max(1e-3, b.targetMspt);
		double gcPercent = b.wallMs == 0 ? 0 : b.gcTimeMs * 100.0 / b.wallMs;
		return new Kpi(b.tps(targetTps), targetTps, b.msptAvg(), b.tickNsMin / 1e6, TickHistogram.percentile(b.msptHistogram, 0.5),
				TickHistogram.percentile(b.msptHistogram, 0.95), b.tickNsMax / 1e6, b.targetMspt,
				b.cpuProcessAvg(), b.cpuSystemAvg(), b.cpuProcessMax, b.heapUsedAvg(), b.heapUsedMax, b.heapMax, b.heapLive, b.heapLiveMax,
				gcPercent, b.gcCount, b.latest, b.max, b.seconds, b.samples,
				eu.explorerseden.nicecontrolcenter.core.SystemMetrics.cores());
	}

	public static List<PhaseView> phases(Breakdown b) {
		Map<Phase, Double> totals = new LinkedHashMap<>();
		Map<Phase, List<DimensionShare>> dims = new HashMap<>();
		double attributed = 0;
		for (Map.Entry<String, Breakdown.Row> entry : b.phases.entrySet()) {
			String[] key = entry.getKey().split("\\|", 2);
			Phase phase;
			try {
				phase = Phase.valueOf(key[1]);
			} catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
				continue;
			}
			double ms = b.perTick(entry.getValue().selfNs);
			attributed += ms;
			totals.merge(phase, ms, Double::sum);
			if (!key[0].equals(Tracker.SERVER_DIMENSION)) {
				dims.computeIfAbsent(phase, k -> new ArrayList<>()).add(new DimensionShare(key[0], ms));
			}
		}
		double other = Math.max(0, b.msptAvg() - attributed);
		totals.merge(Phase.OTHER, other, Double::sum);

		List<PhaseView> result = new ArrayList<>();
		totals.forEach((phase, ms) -> {
			List<DimensionShare> shares = new ArrayList<>(dims.getOrDefault(phase, List.of()));
			shares.sort(Comparator.comparingDouble(DimensionShare::ms).reversed());
			result.add(new PhaseView(phase.name(), phase.label, phase.description, ms, percent(b, ms), shares));
		});
		result.sort(Comparator.comparingDouble(PhaseView::ms).reversed());
		return result;
	}

	public static List<PackView> packs(Breakdown b) {
		Map<String, List<FunctionView>> byPack = new HashMap<>();
		Map<String, Double> packMs = new HashMap<>();
		for (Map.Entry<String, Breakdown.FunctionRow> entry : b.functions.entrySet()) {
			Breakdown.FunctionRow row = entry.getValue();
			String packKey = row.pack == null ? SourceIndex.COMMANDS.id() : row.pack;
			double ms = b.perTick(row.selfNs);
			List<LineView> lines = new ArrayList<>();
			row.lines.forEach((text, line) -> lines.add(new LineView(text, b.perTick(line.selfNs),
					b.ticks == 0 ? 0 : line.runs / (double) b.ticks,
					line.runs == 0 ? 0 : line.entries / (double) line.runs)));
			lines.sort(Comparator.comparingDouble(LineView::ms).reversed());
			String id = entry.getKey();
			if (id.equals("nicecontrolcenter:direct_commands")) {
				id = "(typed or command block commands)";
			}
			byPack.computeIfAbsent(packKey, k -> new ArrayList<>()).add(new FunctionView(id, ms, percent(b, ms),
					b.ticks == 0 ? 0 : row.runs / (double) b.ticks, row.calledFrom, limit(lines, 12)));
			packMs.merge(packKey, ms, Double::sum);
		}
		List<PackView> result = new ArrayList<>();
		byPack.forEach((packKey, functions) -> {
			functions.sort(Comparator.comparingDouble(FunctionView::ms).reversed());
			SourceIndex.Source source = packKey.equals(SourceIndex.COMMANDS.id()) ? SourceIndex.COMMANDS : SourceIndex.pack(packKey);
			double ms = packMs.get(packKey);
			result.add(new PackView(packKey, source.name(), source.kind().name().toLowerCase(), ms, percent(b, ms), limit(functions, TOP)));
		});
		result.sort(Comparator.comparingDouble(PackView::ms).reversed());
		return result;
	}

	public static List<ModView> mods(Breakdown b) {
		List<ModView> result = new ArrayList<>();
		if (b.samples == 0) {
			return result;
		}
		double mspt = b.msptAvg();
		for (Map.Entry<String, Breakdown.ModRow> entry : b.mods.entrySet()) {
			String id = entry.getKey();
			Breakdown.ModRow row = entry.getValue();
			double self = row.self * 100.0 / b.samples;
			double caused = row.caused * 100.0 / b.samples;
			double stack = row.total * 100.0 / b.samples;
			double ms = (self + caused) / 100.0 * mspt;
			List<MethodView> methods = new ArrayList<>();
			row.methods.forEach((name, count) -> methods.add(new MethodView(name, count * 100.0 / b.samples)));
			methods.sort(Comparator.comparingDouble(MethodView::percent).reversed());
			String name = id.equals("minecraft") ? "Minecraft (vanilla code)" : SourceIndex.mod(id).name();
			result.add(new ModView(id, name, ms, percent(b, ms), self, caused, stack, limit(methods, 8)));
		}
		result.sort(Comparator.comparingDouble(ModView::ms).reversed());
		return result;
	}

	public static List<TypeView> entities(Breakdown b) {
		return types(b, b.entities);
	}

	public static List<TypeView> blockEntities(Breakdown b) {
		return types(b, b.blockEntities);
	}

	private static List<TypeView> types(Breakdown b, Map<String, Breakdown.Row> rows) {
		List<TypeView> result = new ArrayList<>();
		rows.forEach((id, row) -> {
			double ms = b.perTick(row.selfNs);
			int colon = id.indexOf(':');
			String source = SourceIndex.namespace(colon < 0 ? "minecraft" : id.substring(0, colon)).name();
			double average = b.seconds == 0 ? 0 : row.countSum / (double) b.seconds;
			double micros = row.calls == 0 ? 0 : row.selfNs / 1000.0 / row.calls;
			result.add(new TypeView(id, source, ms, percent(b, ms), average, row.countMax, micros));
		});
		result.sort(Comparator.comparingDouble(TypeView::ms).reversed().thenComparing(Comparator.comparingDouble(TypeView::averageCount).reversed()));
		return limit(result, 40);
	}

	public static List<HotspotView> hotspots(Breakdown b) {
		List<HotspotView> result = new ArrayList<>();
		for (Breakdown.Hotspot h : b.hotspots.values()) {
			result.add(new HotspotView(h.dimension, h.x, h.z, h.x * 16 + 8, h.z * 16 + 8, b.perTick(h.ns), h.entities, h.blockEntities,
					h.topType, h.topTypeCount));
		}
		result.sort(Comparator.comparingDouble(HotspotView::ms).reversed()
				.thenComparing(Comparator.comparingInt((HotspotView h) -> h.entities() + h.blockEntities()).reversed()));
		return limit(result, 10);
	}

	public static WorldgenView worldgen(Breakdown b) {
		Breakdown.Worldgen w = b.worldgen == null ? new Breakdown.Worldgen() : b.worldgen;
		long chunks = Math.max(0, w.chunks);
		long totalNs = 0;
		for (Breakdown.Timing t : w.stages.values()) {
			totalNs += t.ns;
		}
		double perChunk = chunks == 0 ? 0 : totalNs / 1e6 / chunks;
		double minutes = b.wallMs + b.pausedSeconds * 1000L <= 0 ? 0 : (b.wallMs + b.pausedSeconds * 1000L) / 60_000.0;
		long base = Math.max(1, totalNs);
		long divisor = Math.max(1, chunks);
		List<TimingView> stages = timings(w.stages, divisor, base, false);
		List<TimingView> structures = timings(w.structures, divisor, base, true);
		List<TimingView> features = timings(w.features, divisor, base, true);
		Map<String, long[]> bySource = new HashMap<>();
		Map<String, String> sourceNames = new HashMap<>();
		for (Map<String, Breakdown.Timing> map : List.of(w.structures, w.features)) {
			map.forEach((id, t) -> {
				SourceIndex.Source source = sourceOf(id);
				sourceNames.put(source.id(), source.name());
				long[] acc = bySource.computeIfAbsent(source.id(), k -> new long[2]);
				acc[0] += t.ns;
				acc[1] += t.count;
			});
		}
		List<TimingView> sources = new ArrayList<>();
		bySource.forEach((id, acc) -> sources.add(new TimingView(id, sourceNames.get(id), acc[0] / 1e6 / divisor, acc[0] * 100.0 / base, acc[1])));
		sources.sort(Comparator.comparingDouble(TimingView::msPerChunk).reversed());
		return new WorldgenView(chunks, minutes == 0 ? 0 : chunks / minutes, perChunk, stages, limit(structures, 12), limit(features, 15), limit(sources, 10));
	}

	private static List<TimingView> timings(Map<String, Breakdown.Timing> map, long chunks, long base, boolean withSource) {
		List<TimingView> result = new ArrayList<>();
		map.forEach((id, t) -> result.add(new TimingView(id, withSource ? sourceOf(id).name() : null, t.ns / 1e6 / chunks, t.ns * 100.0 / base, t.count)));
		result.sort(Comparator.comparingDouble(TimingView::msPerChunk).reversed());
		return result;
	}

	private static SourceIndex.Source sourceOf(String id) {
		int colon = id.indexOf(':');
		return SourceIndex.namespace(colon < 0 ? "minecraft" : id.substring(0, colon));
	}

	/** Players seen in the window; "online" means present in the latest second. */
	public static List<PlayerView> players(Breakdown b) {
		List<PlayerView> result = new ArrayList<>();
		if (b.players == null || b.players.isEmpty()) {
			return result;
		}
		double minutes = Math.max(1 / 60.0, (b.wallMs + b.pausedSeconds * 1000L) / 60_000.0);
		long totalChunks = b.worldgen == null ? 0 : Math.max(1, b.worldgen.chunks);
		for (Breakdown.PlayerRow p : b.players.values()) {
			result.add(new PlayerView(p.name, p.dimension, p.x, p.y, p.z, p.ping, p.viewDistance, p.entitiesNear, p.blockEntitiesNear,
					p.newChunks / minutes, p.newChunks * 100.0 / totalChunks, p.lastSeen >= b.end - 3000));
		}
		result.sort(Comparator.comparingDouble(PlayerView::newChunksPerMinute).reversed()
				.thenComparing(Comparator.comparingInt(PlayerView::entitiesNear).reversed()));
		return limit(result, 50);
	}

	/** Busiest block clusters (redstone clocks, piston machines, flowing liquids). */
	public static List<BlockUpdateView> blockUpdates(Breakdown b) {
		List<BlockUpdateView> result = new ArrayList<>();
		if (b.seconds == 0) {
			return result;
		}
		for (Breakdown.BlockUpdates u : b.blockUpdates.values()) {
			if (u.count < b.seconds) {
				// Under one update per second is normal block life (crops, kelp), not a machine.
				continue;
			}
			result.add(new BlockUpdateView(u.dimension, u.x, u.y, u.z, u.block, u.count / (double) b.seconds,
					Math.min(100, u.seconds * 100.0 / b.seconds)));
		}
		result.sort(Comparator.comparingDouble(BlockUpdateView::perSecond).reversed());
		return limit(result, 10);
	}

	public static List<Breakdown.Spike> spikes(Breakdown b) {
		List<Breakdown.Spike> result = new ArrayList<>(b.spikes);
		result.sort(Comparator.comparingDouble((Breakdown.Spike s) -> s.ms).reversed());
		return limit(result, 15);
	}

	/** Everything the dashboard shows for one window. */
	public static Map<String, Object> full(Breakdown b) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("kpi", kpi(b));
		view.put("diagnosis", Diagnoser.diagnose(b));
		view.put("phases", phases(b));
		view.put("packs", packs(b));
		view.put("mods", mods(b));
		view.put("entities", entities(b));
		view.put("blockEntities", blockEntities(b));
		view.put("hotspots", hotspots(b));
		view.put("spikes", spikes(b));
		view.put("blockUpdates", blockUpdates(b));
		view.put("worldgen", worldgen(b));
		view.put("players", players(b));
		view.put("settings", Advisor.checks());
		view.put("samplerIntervalMs", b.samplerIntervalMs);
		view.put("start", b.start);
		view.put("end", b.end);
		return view;
	}

	public static double percentOf(Breakdown b, double ms) {
		return percent(b, ms);
	}

	static double percent(Breakdown b, double ms) {
		return b.targetMspt <= 0 ? 0 : ms * 100.0 / b.targetMspt;
	}

	static <T> List<T> limit(List<T> list, int max) {
		return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
	}
}
