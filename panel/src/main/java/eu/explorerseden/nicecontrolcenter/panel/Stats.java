package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Long-term stats: once a minute the averages of the mod's per-second points (TPS, MSPT, CPU, memory,
 * players, chunks, entities, GC) and the disk, one JSON line per minute in /data/panel/stats/<date>.jsonl.
 * Days older than a week are compacted to 10-minute points, and 90 days are kept. Server starts, stops,
 * crashes and alerts are kept as markers. Also raises the disk and memory alerts.
 */
public final class Stats {
	private static final int KEEP_DAYS = 90;
	private static final int FULL_DAYS = 7;
	private static final int MAX_POINTS = 500;

	private final Path dir;
	private final Supervisor server;
	private final DashboardProxy mod;
	private final ZoneId zone;
	private volatile Notifier notifier = Notifier.NONE;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "stats");
		t.setDaemon(true);
		return t;
	});
	private long lastT;
	private Supervisor.State lastState;
	private int memoryStreak;

	public Stats(Path dir, Supervisor server, DashboardProxy mod, ZoneId zone) {
		this.dir = dir;
		this.server = server;
		this.mod = mod;
		this.zone = zone;
	}

	void useNotifier(Notifier n) {
		notifier = n;
	}

	public void start() {
		timer.scheduleAtFixedRate(this::sample, 60, 60, TimeUnit.SECONDS);
		// State changes are short (a restart takes seconds), so they're watched more often.
		timer.scheduleAtFixedRate(this::watchState, 5, 5, TimeUnit.SECONDS);
		timer.scheduleAtFixedRate(this::compact, 1, 360, TimeUnit.MINUTES);
	}

	// ── Recording ──────────────────────────────────────────────────────────

	private void watchState() {
		try {
			Supervisor.State state = server.state();
			Supervisor.State before = lastState;
			lastState = state;
			if (before == null || before == state) return;
			if (state == Supervisor.State.RUNNING) mark("start", "");
			else if (state == Supervisor.State.CRASHED) mark("crash", "");
			else if (state == Supervisor.State.STOPPED && before != Supervisor.State.CRASHED) mark("stop", "");
		} catch (RuntimeException e) {
			System.err.println("Stats: " + e);
		}
	}

	/** A marker on the charts (start, stop, crash, trimDone, backupFailed, taskFailed, lag). */
	void mark(String type, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("t", System.currentTimeMillis());
		o.addProperty("type", type);
		if (!text.isEmpty()) o.addProperty("text", text.length() > 200 ? text.substring(0, 200) + "…" : text);
		append(dir.resolve("markers.jsonl"), o);
	}

	private void sample() {
		try {
			JsonObject p = new JsonObject();
			long now = System.currentTimeMillis();
			p.addProperty("t", now);
			boolean running = server.state() == Supervisor.State.RUNNING;
			p.addProperty("up", running ? 1 : 0);
			JsonObject live = running ? mod.get("/api/live?after=" + (lastT > 0 ? lastT : now - 60_000)) : null;
			if (live != null) {
				average(live, p);
				JsonElement disk = live.get("disk");
				if (disk != null && disk.isJsonObject()) {
					JsonObject d = disk.getAsJsonObject();
					for (String k : List.of("world", "server", "free", "total")) if (d.has(k)) p.addProperty(k, d.get(k).getAsLong());
				}
			}
			append(dir.resolve(LocalDate.now(zone) + ".jsonl"), p);
			alerts(p);
		} catch (RuntimeException e) {
			System.err.println("Stats: " + e);
		}
	}

	/** Averages the mod's per-second points since the last sample into this minute's point. */
	private void average(JsonObject live, JsonObject p) {
		JsonArray points = live.has("points") && live.get("points").isJsonArray() ? live.getAsJsonArray("points") : new JsonArray();
		int n = 0;
		double tps = 0, mspt = 0, cpu = 0, chunks = 0, entities = 0;
		double msptMax = 0;
		long gc = 0, heapLive = 0, heapMax = 0;
		int players = 0;
		int all = 0;
		for (JsonElement e : points) {
			JsonObject o = e.getAsJsonObject();
			lastT = Math.max(lastT, o.get("t").getAsLong());
			all++;
			players = Math.max(players, o.get("players").getAsInt());
			heapLive = o.get("heapLive").getAsLong();
			heapMax = o.get("heapMax").getAsLong();
			// An empty server pauses its ticks: it's up, but there's no tick time to average.
			if (o.has("paused") && o.get("paused").getAsBoolean()) continue;
			n++;
			tps += o.get("tps").getAsDouble();
			mspt += o.get("mspt").getAsDouble();
			msptMax = Math.max(msptMax, o.get("msptMax").getAsDouble());
			cpu += o.get("cpuProcess").getAsDouble();
			chunks += o.get("chunks").getAsInt();
			entities += o.get("entities").getAsInt();
			gc += o.get("gcMs").getAsLong();
		}
		if (all == 0) return;
		p.addProperty("players", players);
		p.addProperty("heapLive", heapLive);
		p.addProperty("heapMax", heapMax);
		if (n == 0) {
			p.addProperty("paused", 1);
			return;
		}
		p.addProperty("tps", round(tps / n));
		p.addProperty("mspt", round(mspt / n));
		p.addProperty("msptMax", round(msptMax));
		p.addProperty("cpu", round(cpu / n));
		p.addProperty("chunks", Math.round(chunks / n));
		p.addProperty("entities", Math.round(entities / n));
		p.addProperty("gcMs", gc);
		p.addProperty("seconds", n);
	}

	private void alerts(JsonObject p) {
		if (p.has("free") && p.has("total") && p.get("total").getAsLong() > 0) {
			double free = p.get("free").getAsLong() * 100.0 / p.get("total").getAsLong();
			if (free < 10) notifier.alert("diskLow", Map.of("message", Backups.human(p.get("free").getAsLong()) + " free (" + Math.round(free) + "%)"));
		}
		if (p.has("heapLive") && p.get("heapMax").getAsLong() > 0) {
			double used = p.get("heapLive").getAsLong() * 100.0 / p.get("heapMax").getAsLong();
			memoryStreak = used >= 90 ? memoryStreak + 1 : 0;
			if (memoryStreak >= 5) {
				notifier.alert("memory", Map.of("message", Math.round(used) + "% of " + Backups.human(p.get("heapMax").getAsLong())
						+ " still in use after garbage collection for 5 minutes; give the server more memory (Startup & Java)"));
			}
		} else {
			memoryStreak = 0;
		}
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private synchronized void append(Path file, JsonObject o) {
		try {
			Files.createDirectories(dir);
			Files.writeString(file, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("Stats: could not write " + file + ": " + e.getMessage());
		}
	}

	/** Days older than a week become 10-minute points; days older than 90 are deleted (markers too). */
	private synchronized void compact() {
		try {
			if (!Files.isDirectory(dir)) return;
			LocalDate today = LocalDate.now(zone);
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.toList()) {
					String name = f.getFileName().toString();
					if (!name.matches("\\d{4}-\\d{2}-\\d{2}(\\.10m)?\\.jsonl")) continue;
					LocalDate day = LocalDate.parse(name.substring(0, 10));
					if (day.isBefore(today.minusDays(KEEP_DAYS))) {
						Files.delete(f);
					} else if (!name.contains(".10m") && day.isBefore(today.minusDays(FULL_DAYS))) {
						List<JsonObject> points = read(f);
						Files.writeString(dir.resolve(day + ".10m.jsonl"), String.join("\n", buckets(points, 600_000L).stream().map(JsonObject::toString).toList()) + "\n");
						Files.delete(f);
					}
				}
			}
			Path markers = dir.resolve("markers.jsonl");
			if (Files.exists(markers)) {
				long cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L;
				List<JsonObject> kept = read(markers).stream().filter(o -> o.get("t").getAsLong() >= cutoff).toList();
				Files.writeString(markers, kept.isEmpty() ? "" : String.join("\n", kept.stream().map(JsonObject::toString).toList()) + "\n");
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Stats: compaction failed: " + e);
		}
	}

	// ── Reading ────────────────────────────────────────────────────────────

	private static List<JsonObject> read(Path f) {
		List<JsonObject> out = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				try {
					out.add(JsonParser.parseString(line).getAsJsonObject());
				} catch (RuntimeException e) {
					// A line cut off by a crash.
				}
			}
		} catch (IOException e) {
			// Gone meanwhile.
		}
		return out;
	}

	/** All points between from and to (epoch millis), from the daily files. */
	private List<JsonObject> points(long from, long to) {
		List<JsonObject> out = new ArrayList<>();
		LocalDate first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
		LocalDate last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate();
		for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
			for (String name : List.of(d + ".jsonl", d + ".10m.jsonl")) {
				Path f = dir.resolve(name);
				if (!Files.exists(f)) continue;
				for (JsonObject o : read(f)) {
					long t = o.get("t").getAsLong();
					if (t >= from && t <= to) out.add(o);
				}
			}
		}
		out.sort((a, b) -> Long.compare(a.get("t").getAsLong(), b.get("t").getAsLong()));
		return out;
	}

	/**
	 * Averages points into buckets of the given length. MSPT max, players and memory take the highest
	 * value, sizes the latest, the rest the average; up = share of the time the server ran.
	 */
	private static List<JsonObject> buckets(List<JsonObject> points, long size) {
		Map<Long, List<JsonObject>> groups = new LinkedHashMap<>();
		for (JsonObject p : points) groups.computeIfAbsent(p.get("t").getAsLong() / size * size, k -> new ArrayList<>()).add(p);
		List<JsonObject> out = new ArrayList<>();
		for (Map.Entry<Long, List<JsonObject>> g : groups.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("t", g.getKey());
			List<JsonObject> ps = g.getValue();
			o.addProperty("up", round(ps.stream().mapToDouble(p -> p.has("up") ? p.get("up").getAsDouble() : 0).average().orElse(0)));
			for (String k : List.of("tps", "mspt", "cpu", "chunks", "entities")) {
				var vals = ps.stream().filter(p -> p.has(k)).mapToDouble(p -> p.get(k).getAsDouble()).toArray();
				if (vals.length > 0) o.addProperty(k, round(java.util.Arrays.stream(vals).average().orElse(0)));
			}
			for (String k : List.of("msptMax", "players", "heapLive")) {
				var vals = ps.stream().filter(p -> p.has(k)).mapToDouble(p -> p.get(k).getAsDouble()).toArray();
				if (vals.length > 0) o.addProperty(k, round(java.util.Arrays.stream(vals).max().orElse(0)));
			}
			long gc = 0, seconds = 0;
			for (JsonObject p : ps) {
				if (p.has("gcMs")) gc += p.get("gcMs").getAsLong();
				if (p.has("seconds")) seconds += p.get("seconds").getAsLong();
			}
			if (seconds > 0) {
				o.addProperty("gcMs", gc);
				o.addProperty("seconds", seconds);
			}
			for (String k : List.of("heapMax", "world", "server", "free", "total")) {
				for (int i = ps.size() - 1; i >= 0; i--) {
					if (ps.get(i).has(k)) {
						o.add(k, ps.get(i).get(k));
						break;
					}
				}
			}
			out.add(o);
		}
		return out;
	}

	/** Points over the range ("24h", "7d", "30d", "90d"), at most 500, plus markers and the disk forecast. */
	public Map<String, Object> series(String range) {
		long span = switch (range == null ? "" : range) {
			case "7d" -> 7 * 86_400_000L;
			case "30d" -> 30 * 86_400_000L;
			case "90d" -> 90 * 86_400_000L;
			default -> 86_400_000L;
		};
		long to = System.currentTimeMillis();
		long from = to - span;
		List<JsonObject> raw = points(from, to);
		// Never finer than a minute; coarser so there are at most MAX_POINTS.
		long size = Math.max(60_000L, (span / MAX_POINTS + 59_999) / 60_000 * 60_000);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("step", size);
		out.put("points", buckets(raw, size));
		Path markers = dir.resolve("markers.jsonl");
		out.put("markers", Files.exists(markers) ? read(markers).stream().filter(o -> o.get("t").getAsLong() >= from).toList() : List.of());
		out.put("forecast", forecast());
		return out;
	}

	private volatile double uptimeCache = -1;
	private volatile long uptimeAt;

	/** Share of the last 7 days the server was running (0–100, one decimal), or -1 without samples. Cached 5 minutes. */
	public double uptime7d() {
		long now = System.currentTimeMillis();
		if (now - uptimeAt < 300_000) return uptimeCache;
		List<JsonObject> ps = points(now - 7 * 86_400_000L, now);
		uptimeCache = ps.isEmpty() ? -1 : Math.round(ps.stream().mapToDouble(p -> p.has("up") ? p.get("up").getAsDouble() : 0).average().orElse(0) * 1000) / 10.0;
		uptimeAt = now;
		return uptimeCache;
	}

	/** The newest sample (free disk, memory), or null. */
	public JsonObject latest() {
		long now = System.currentTimeMillis();
		List<JsonObject> ps = points(now - 3_600_000L, now);
		return ps.isEmpty() ? null : ps.get(ps.size() - 1);
	}

	/** Days until the drive is full, from how free space shrank over the last 14 days (null if not shrinking). */
	private Map<String, Object> forecast() {
		long to = System.currentTimeMillis();
		List<JsonObject> ps = points(to - 14 * 86_400_000L, to).stream().filter(p -> p.has("free")).toList();
		if (ps.size() < 60) return null;
		double n = ps.size(), sx = 0, sy = 0, sxx = 0, sxy = 0;
		double t0 = ps.get(0).get("t").getAsDouble();
		for (JsonObject p : ps) {
			double x = (p.get("t").getAsDouble() - t0) / 86_400_000.0;
			double y = p.get("free").getAsDouble();
			sx += x;
			sy += y;
			sxx += x * x;
			sxy += x * y;
		}
		double slope = (n * sxy - sx * sy) / Math.max(1e-9, n * sxx - sx * sx); // bytes per day
		JsonObject last = ps.get(ps.size() - 1);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("perDay", Math.round(-slope));
		out.put("free", last.get("free").getAsLong());
		if (slope < 0) out.put("days", Math.round(last.get("free").getAsDouble() / -slope));
		return out;
	}

	/** Average players online per weekday (1 = Monday) and hour over the last days. */
	public Map<String, Object> heatmap(int days) {
		long to = System.currentTimeMillis();
		double[][] sum = new double[7][24];
		int[][] count = new int[7][24];
		for (JsonObject p : points(to - days * 86_400_000L, to)) {
			if (!p.has("up")) continue;
			ZonedDateTime at = Instant.ofEpochMilli(p.get("t").getAsLong()).atZone(zone);
			int d = at.getDayOfWeek().getValue() - 1;
			int h = at.getHour();
			sum[d][h] += p.has("players") ? p.get("players").getAsDouble() : 0;
			count[d][h]++;
		}
		List<List<Double>> grid = new ArrayList<>();
		for (int d = 0; d < 7; d++) {
			List<Double> row = new ArrayList<>();
			for (int h = 0; h < 24; h++) row.add(count[d][h] == 0 ? null : round(sum[d][h] / count[d][h]));
			grid.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("days", days);
		out.put("zone", zone.getId());
		out.put("grid", grid);
		return out;
	}
}
