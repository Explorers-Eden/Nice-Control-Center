package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonObject;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Performance;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.record.Recorder;
import net.minecraft.server.MinecraftServer;

/**
 * A/B trials: the server switches between two sets of performance settings (and distances) in turns, A B B A
 * A B …, so a change in players or time of day hits both sides alike. Each turn's first seconds are skipped (the
 * world settles after a switch), turns where the player count changed a lot are run again, and at the end both
 * sides are compared over all their turns. A difference only counts when it's bigger than the spread between
 * turns of the same side; otherwise the verdict says there's no measurable difference.
 *
 * <p>Changes are in memory only: stopping the trial, a crash or a restart is the normal settings again.
 */
public final class Trials {
	/** One side: its name, the settings it lays over the file's, and view/simulation distance (0: as they were). */
	public record Side(String token, String label, JsonObject patch, int view, int sim) {
	}

	public record Spec(Side a, Side b, int segmentSeconds, int rounds, String startedBy) {
	}

	/** One turn's numbers. */
	public record Segment(String side, long start, long end, boolean valid, String note, double players, Map<String, Double> values) {
	}

	/** One measure over all turns of both sides; change is B against A in percent. */
	public record Metric(String key, String label, String unit, boolean lowerIsBetter, double meanA, double spreadA, double meanB, double spreadB,
			double change, boolean clear) {
	}

	public record Result(String file, String a, String b, long started, long ended, int segmentSeconds, int rounds, String startedBy,
			String verdict, String winner, List<Metric> metrics, List<Segment> segments, boolean complete) {
	}

	public record Status(boolean active, String a, String b, String current, int segment, int segments, long segmentEndsAt, long endsAt,
			String startedBy, List<Segment> done) {
	}

	/** Features a side can switch on or off by name. */
	private static final Map<String, String> FEATURES = Map.ofEntries(
			Map.entry("dynamic", "{\"dynamic\":{\"enabled\":%s}}"),
			Map.entry("activation", "{\"activation\":{\"enabled\":%s}}"),
			Map.entry("ai", "{\"ai\":{\"enabled\":%s}}"),
			Map.entry("hoppers", "{\"hoppers\":{\"enabled\":%s}}"),
			Map.entry("merge", "{\"merge\":{\"enabled\":%s}}"),
			Map.entry("collisions", "{\"collisions\":{\"enabled\":%s}}"),
			Map.entry("despawn", "{\"despawn\":{\"enabled\":%s}}"),
			Map.entry("savelimits", "{\"saveLimits\":{\"enabled\":%s}}"),
			Map.entry("armorstands", "{\"decor\":{\"armorStands\":%s}}"),
			Map.entry("villagers", "{\"villagers\":{\"lobotomize\":%s}}"),
			Map.entry("breeding", "{\"breeding\":{\"enabled\":%s}}"),
			Map.entry("autocaps", "{\"autoCaps\":{\"enabled\":%s}}"),
			Map.entry("smoothsaving", "{\"smooth\":{\"saving\":%s}}"),
			Map.entry("warmteleports", "{\"smooth\":{\"teleports\":%s}}"),
			Map.entry("spawning", "{\"spawning\":{\"enabled\":%s}}"));

	private static final int WARMUP_SECONDS = 30;
	private static final int MAX_REPEATS = 2;

	private static volatile Spec spec;
	private static Side current;
	private static int index;
	private static int repeats;
	private static long started;
	private static long segmentStart;
	private static Breakdown acc;
	private static double playerSum;
	private static int playerSeconds;
	private static int playersMin;
	private static int playersMax;
	private static boolean pausedSeen;
	private static int originalView = -1;
	private static int originalSim = -1;
	private static final List<Segment> segments = new ArrayList<>();

	private Trials() {
	}

	/** The server is stopping: forget the trial (its changes were never written anywhere). */
	public static synchronized void reset() {
		spec = null;
		current = null;
		segments.clear();
	}

	public static boolean active() {
		return spec != null;
	}

	/** The settings to use: the file's, with the current side's changes during a trial. */
	public static Performance.Settings effective(Performance.Settings file) {
		Side side = current;
		if (spec == null || side == null || side.patch() == null || side.patch().isEmpty()) return file;
		return Performance.patched(file, side.patch());
	}

	/**
	 * A side from its token: current, off, balanced, aggressive (profiles), sim6 / view8 (distances), activation /
	 * no-activation (a feature on or off; see {@link #FEATURES}). Tokens can be joined with '+'. Null when unknown.
	 */
	public static Side side(String token) {
		String t = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
		if (t.isEmpty()) return null;
		JsonObject patch = new JsonObject();
		int view = 0;
		int sim = 0;
		List<String> labels = new ArrayList<>();
		for (String part : t.split("\\+")) {
			if (part.equals("current")) {
				labels.add("current settings");
			} else if (Performance.PROFILES.contains(part)) {
				mergeInto(patch, Performance.profile(part));
				labels.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1) + " profile");
			} else if (part.matches("sim\\d{1,2}")) {
				sim = Math.max(2, Math.min(32, Integer.parseInt(part.substring(3))));
				labels.add("simulation distance " + sim);
			} else if (part.matches("view\\d{1,2}")) {
				view = Math.max(2, Math.min(32, Integer.parseInt(part.substring(4))));
				labels.add("view distance " + view);
			} else {
				boolean on = !part.startsWith("no-");
				String feature = on ? part : part.substring(3);
				String template = FEATURES.get(feature);
				if (template == null) return null;
				mergeInto(patch, com.google.gson.JsonParser.parseString(String.format(Locale.ROOT, template, on)).getAsJsonObject());
				labels.add(feature + (on ? " on" : " off"));
			}
		}
		return new Side(t, String.join(" + ", labels), patch, view, sim);
	}

	private static void mergeInto(JsonObject into, JsonObject patch) {
		patch.entrySet().forEach(e -> {
			if (into.has(e.getKey()) && into.get(e.getKey()).isJsonObject() && e.getValue().isJsonObject()) {
				mergeInto(into.getAsJsonObject(e.getKey()), e.getValue().getAsJsonObject());
			} else {
				into.add(e.getKey(), e.getValue().deepCopy());
			}
		});
	}

	/** Starts a trial; an error message, or null. Server thread. */
	public static synchronized String start(MinecraftServer server, String aToken, String bToken, int segmentMinutes, int rounds, String by) {
		if (spec != null) return "A trial is already running. Stop it first with /ncc trial stop.";
		Side a = side(aToken);
		Side b = side(bToken);
		if (a == null) return "Unknown side A: " + aToken;
		if (b == null) return "Unknown side B: " + bToken;
		if (a.token().equals(b.token())) return "Both sides are the same.";
		int seconds = Math.max(2, Math.min(60, segmentMinutes)) * 60;
		int r = Math.max(1, Math.min(12, rounds));
		// Lag protection's steps are undone first; it stays paused while the trial runs.
		PerfControl.restore(server, "trial started");
		originalView = server.getPlayerList().getViewDistance();
		originalSim = server.getPlayerList().getSimulationDistance();
		segments.clear();
		spec = new Spec(a, b, seconds, r, by == null ? "?" : by);
		started = System.currentTimeMillis();
		index = 0;
		repeats = 0;
		begin(server);
		NiceControlCenter.LOGGER.info("Trial started by {}: A = {} vs B = {}, {} turns of {} min", spec.startedBy(), a.label(), b.label(), r * 2, seconds / 60);
		return null;
	}

	/** Ends the trial early; the verdict uses the turns done so far. Server thread. */
	public static synchronized Result stop(MinecraftServer server, String why) {
		if (spec == null) return null;
		return finish(server, false, why);
	}

	/** A or B for turn i: A B, B A, A B, … */
	private static boolean isA(int i) {
		boolean firstIsA = (i / 2) % 2 == 0;
		return i % 2 == 0 ? firstIsA : !firstIsA;
	}

	private static void begin(MinecraftServer server) {
		Spec s = spec;
		current = isA(index) ? s.a() : s.b();
		segmentStart = System.currentTimeMillis();
		acc = new Breakdown();
		playerSum = 0;
		playerSeconds = 0;
		playersMin = Integer.MAX_VALUE;
		playersMax = 0;
		pausedSeen = false;
		PerfControl.apply(server);
		var list = server.getPlayerList();
		int view = current.view() > 0 ? current.view() : originalView;
		int sim = current.sim() > 0 ? current.sim() : originalSim;
		if (list.getViewDistance() != view) list.setViewDistance(view);
		if (list.getSimulationDistance() != sim) list.setSimulationDistance(sim);
	}

	/** Once a second, on the server thread, with the second just measured. */
	public static synchronized void onSecond(MinecraftServer server, Breakdown second) {
		Spec s = spec;
		if (s == null) return;
		long now = System.currentTimeMillis();
		long elapsed = now - segmentStart;
		if (elapsed >= WARMUP_SECONDS * 1000L) {
			acc.merge(second);
			if (second.pausedSeconds > 0) pausedSeen = true;
			int p = second.latest.players;
			playerSum += p;
			playerSeconds++;
			playersMin = Math.min(playersMin, p);
			playersMax = Math.max(playersMax, p);
		}
		if (elapsed < s.segmentSeconds() * 1000L) return;
		String note = null;
		if (acc.ticks == 0) note = "nothing measured";
		else if (pausedSeen) note = "the server paused (nobody online)";
		else if (playersMax - playersMin > 2) note = "players changed from " + playersMin + " to " + playersMax;
		boolean valid = note == null;
		double players = playerSeconds == 0 ? 0 : playerSum / playerSeconds;
		segments.add(new Segment(isA(index) ? "A" : "B", segmentStart, now, valid, note, players, values(acc, players)));
		if (!valid && repeats < MAX_REPEATS) {
			// The same side again, so both sides still get their turns.
			repeats++;
		} else {
			repeats = 0;
			index++;
		}
		if (index >= s.rounds() * 2) {
			finish(server, true, null);
		} else {
			begin(server);
		}
	}

	/** The numbers compared, from one turn. */
	private static Map<String, Double> values(Breakdown b, double players) {
		Map<String, Double> v = new LinkedHashMap<>();
		double seconds = Math.max(1e-3, b.wallMs / 1000.0);
		double busy = (b.tickNsSum + b.betweenBusyNs) / 1e6 / seconds;
		v.put("busy", busy);
		v.put("tps", b.tps(1000.0 / Math.max(1e-3, b.targetMspt)));
		v.put("msptMedian", TickHistogram.percentile(b.msptHistogram, 0.5));
		v.put("msptP95", TickHistogram.percentile(b.msptHistogram, 0.95));
		v.put("between", b.betweenBusyNs / 1e6 / seconds);
		v.put("offCpu", Snapshotter.offCpuNs(b) / 1e6 / seconds);
		v.put("perPlayer", busy / Math.max(1, players));
		return v;
	}

	private static final String[][] METRICS = {
			{"busy", "Server thread busy", "ms/s", "1"},
			{"tps", "TPS", "", "0"},
			{"msptMedian", "MSPT median", "ms", "1"},
			{"msptP95", "MSPT 95%", "ms", "1"},
			{"between", "Work between ticks", "ms/s", "1"},
			{"offCpu", "Off the CPU", "ms/s", "1"},
			{"perPlayer", "Busy per player", "ms/s", "1"},
	};

	private static Result finish(MinecraftServer server, boolean complete, String why) {
		Spec s = spec;
		spec = null;
		current = null;
		PerfControl.apply(server);
		var list = server.getPlayerList();
		if (originalView > 0 && list.getViewDistance() != originalView) list.setViewDistance(originalView);
		if (originalSim > 0 && list.getSimulationDistance() != originalSim) list.setSimulationDistance(originalSim);
		List<Metric> metrics = new ArrayList<>();
		for (String[] m : METRICS) {
			List<Double> a = new ArrayList<>();
			List<Double> b = new ArrayList<>();
			for (Segment seg : segments) {
				if (!seg.valid()) continue;
				(seg.side().equals("A") ? a : b).add(seg.values().get(m[0]));
			}
			double ma = mean(a), mb = mean(b), sa = spread(a), sb = spread(b);
			double change = ma == 0 ? 0 : (mb - ma) * 100 / Math.abs(ma);
			// Clear: both sides measured at least twice, the gap is bigger than both spreads together and not tiny.
			boolean clear = a.size() >= 2 && b.size() >= 2 && Math.abs(mb - ma) > sa + sb && Math.abs(change) >= 3;
			metrics.add(new Metric(m[0], m[1], m[2], m[3].equals("1"), ma, sa, mb, sb, change, clear));
		}
		String winner = null;
		String verdict;
		Metric busy = metrics.get(0);
		Metric tps = metrics.get(1);
		if (busy.clear()) {
			winner = busy.meanB() < busy.meanA() ? "B" : "A";
		} else if (tps.clear()) {
			winner = tps.meanB() > tps.meanA() ? "B" : "A";
		}
		String aLabel = s.a().label();
		String bLabel = s.b().label();
		if (winner == null) {
			verdict = "No measurable difference between " + aLabel + " and " + bLabel + ": the gap is smaller than the spread between turns.";
		} else {
			Metric m = busy.clear() ? busy : tps;
			String better = winner.equals("A") ? aLabel : bLabel;
			double win = winner.equals("A") ? m.meanA() : m.meanB();
			double lose = winner.equals("A") ? m.meanB() : m.meanA();
			// Against the worse side: "78 % lower" means the better one needs 78 % less.
			double gain = lose == 0 ? 0 : Math.abs(lose - win) * 100 / Math.abs(lose);
			verdict = String.format(Locale.ROOT, "%s is better: %s %.0f%% %s (%.1f vs %.1f%s).", better, m.label().toLowerCase(Locale.ROOT), gain,
					m.lowerIsBetter() ? "lower" : "higher", winner.equals("A") ? m.meanA() : m.meanB(),
					winner.equals("A") ? m.meanB() : m.meanA(), m.unit().isEmpty() ? "" : " " + m.unit());
		}
		if (!complete) verdict = "Stopped early" + (why == null ? "" : " (" + why + ")") + ". " + verdict;
		long now = System.currentTimeMillis();
		String name = "trial-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(started)) + ".json";
		Result result = new Result(name, aLabel, bLabel, started, now, s.segmentSeconds(), s.rounds(), s.startedBy(), verdict, winner, metrics,
				new ArrayList<>(segments), complete);
		segments.clear();
		NiceControlCenter.LOGGER.info("Trial finished: {}", verdict);
		Path dir = dir(server);
		String json = Json.GSON.newBuilder().setPrettyPrinting().create().toJson(result);
		// Written off the server thread.
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				Files.createDirectories(dir);
				Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
			} catch (IOException e) {
				NiceControlCenter.LOGGER.warn("Couldn't save the trial result: {}", e.toString());
			}
		});
		return result;
	}

	private static double mean(List<Double> v) {
		return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).sum() / v.size();
	}

	/** Standard deviation (0 for fewer than two values). */
	private static double spread(List<Double> v) {
		if (v.size() < 2) return 0;
		double m = mean(v);
		double sum = 0;
		for (double x : v) sum += (x - m) * (x - m);
		return Math.sqrt(sum / (v.size() - 1));
	}

	public static synchronized Status status() {
		Spec s = spec;
		if (s == null) return new Status(false, null, null, null, 0, 0, 0, 0, null, List.of());
		int total = s.rounds() * 2;
		long segEnd = segmentStart + s.segmentSeconds() * 1000L;
		long end = segEnd + (long) (total - index - 1) * s.segmentSeconds() * 1000L;
		return new Status(true, s.a().label(), s.b().label(), isA(index) ? "A" : "B", index + 1, total, segEnd, end, s.startedBy(), new ArrayList<>(segments));
	}

	public static Path dir(MinecraftServer server) {
		return Recorder.baseDir(server).resolve("trials");
	}

	/** Saved results, newest first. */
	public static List<Result> results(MinecraftServer server, int max) {
		List<Result> out = new ArrayList<>();
		Path dir = dir(server);
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted((x, y) -> y.getFileName().compareTo(x.getFileName())).limit(max).toList()) {
				try {
					Result r = Json.GSON.fromJson(Files.readString(f), Result.class);
					if (r != null) out.add(r);
				} catch (IOException | RuntimeException e) {
					// Skip unreadable files.
				}
			}
		} catch (IOException e) {
			return out;
		}
		return out;
	}

	/** Tokens a side can be made of, for menus and tab completion. */
	public static List<String> tokens() {
		List<String> t = new ArrayList<>(List.of("current", "off", "balanced", "aggressive"));
		FEATURES.keySet().stream().sorted().forEach(f -> {
			t.add(f);
			t.add("no-" + f);
		});
		return t;
	}
}
