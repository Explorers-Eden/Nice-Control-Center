package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import eu.explorerseden.nicecontrolcenter.core.Phase;
import eu.explorerseden.nicecontrolcenter.core.SourceIndex;
import eu.explorerseden.nicecontrolcenter.core.TickHistogram;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.Point;

/**
 * Plain-language answer to "what is slowing the server down, and why?".
 *
 * <p>Every cost is put in the same unit, milliseconds per tick and percent of the tick budget, so a
 * data pack, a mod, a mob type and chunk loading can be compared directly. Each finding has a
 * short reason built from the measurements and a hint about what usually helps.
 */
public final class Diagnoser {
	public enum Severity {
		POOR, WARN, INFO, GOOD
	}

	public record Health(Severity status, String title, String headline, String summary) {
	}

	public record Finding(Severity severity, String category, String title, double ms, double percent, String reason,
			String hint) {
	}

	/**
	 * {@code findings} are what costs performance right now; {@code notes} are settings and bloat
	 * worth a look, kept apart so they don't crowd out the real causes.
	 */
	public record Diagnosis(Health health, List<Finding> findings, List<Finding> notes) {
	}

	private static final int MAX_FINDINGS = 12;
	private static final double MIN_PERCENT = 1.5;
	private static final long MIN_SAMPLES = 100;

	private Diagnoser() {
	}

	public static Diagnosis diagnose(Breakdown b) {
		return diagnose(b, List.of());
	}

	/** With the window's chart points (one per second), stretches of lag inside a long window are found too. */
	public static Diagnosis diagnose(Breakdown b, List<Point> points) {
		List<Finding> findings = new ArrayList<>();
		if (b.ticks == 0) {
			if (b.pausedSeconds > 0) {
				return new Diagnosis(new Health(Severity.INFO, "The server is paused", "Nobody is online, so the world isn't ticking.",
						"Minecraft pauses an empty server after pause-when-empty-seconds (server.properties). Monitoring continues when someone joins."), findings, notes());
			}
			return new Diagnosis(new Health(Severity.INFO, "Waiting for data", "No ticks measured in this window yet.",
					"Data appears a few seconds after the server starts or monitoring is switched on."), findings, notes());
		}
		datapacks(b, findings);
		mods(b, findings);
		types(b, Views.entities(b), "Entities", findings);
		types(b, Views.blockEntities(b), "Block entities", findings);
		world(b, findings);
		redstone(b, findings);
		worldgen(b, findings);
		explorers(b, findings);
		spikes(b, findings);
		between(b, findings);
		stretches(b, points, findings);
		offCpu(b, findings);
		host(b, findings);
		memory(b, findings);
		memoryGrowth(points, findings);
		cpu(b, findings);

		findings.sort(Comparator.comparingInt((Finding f) -> f.severity().ordinal()).thenComparing(Comparator.comparingDouble(Finding::ms).reversed()));
		List<Finding> top = Views.limit(findings, MAX_FINDINGS);
		return new Diagnosis(health(b, top), top, notes());
	}

	private static List<Finding> notes() {
		List<Finding> notes = new ArrayList<>();
		eu.explorerseden.nicecontrolcenter.log.ErrorWatcher.problemsBySource(true).forEach((source, groups) -> {
			long count = groups.stream().mapToLong(g -> g.sinceReload).sum();
			notes.add(new Finding(Severity.WARN, "Errors", source + ": " + count + (count == 1 ? " error" : " errors") + " in the log", 0, 0,
					"For example: " + shorten(groups.get(0).example, 160), "See the Server tab for all errors and where they come from."));
		});
		Advisor.addFindings(notes);
		BloatCheck.addFindings(notes);
		return notes;
	}

	// ── Health ──────────────────────────────────────────────────────────────

	private static Health health(Breakdown b, List<Finding> findings) {
		Views.Kpi kpi = Views.kpi(b);
		double load = kpi.msptAvg() / b.targetMspt * 100;
		String headline = String.format(Locale.ROOT, "%.1f TPS · MSPT %.1f min / %.1f med / %.1f 95%% / %.1f max · %.0f%% of the %.0f ms budget",
				kpi.tps(), kpi.msptMin(), kpi.msptMedian(), kpi.msptP95(), kpi.msptMax(), load, b.targetMspt);

		Severity status;
		String title;
		if (kpi.tps() < kpi.targetTps() * 0.95 || kpi.msptAvg() > b.targetMspt) {
			status = Severity.POOR;
			title = "The server is lagging";
		} else if (load > 70 || kpi.msptP95() > b.targetMspt || slowTickShare(b) > 0.02) {
			status = Severity.WARN;
			title = "The server is keeping up, but close to its limit";
		} else if (findings.stream().anyMatch(f -> f.category().equals(STRETCHES) && f.severity().ordinal() <= Severity.WARN.ordinal())) {
			// The averages look fine, but part of the window was close to the limit.
			status = Severity.WARN;
			title = "Smooth on average, but with stretches close to the limit";
		} else {
			status = Severity.GOOD;
			title = "The server is running smoothly";
		}

		List<String> causes = new ArrayList<>();
		for (Finding finding : findings) {
			if (finding.ms() > 0 && causes.size() < 3) {
				causes.add(finding.category().toLowerCase(Locale.ROOT) + " " + quote(finding.title()) + " (" + ms(finding.ms()) + ")");
			}
		}
		String summary;
		if (causes.isEmpty()) {
			summary = status == Severity.GOOD ? "Nothing stands out." : "No single cause stands out; see the breakdown below.";
		} else {
			summary = (status == Severity.GOOD ? "Largest costs: " : "Mainly caused by: ") + String.join(", ", causes) + ".";
		}
		return new Health(status, title, headline, summary);
	}

	/**
	 * Share of ticks over budget. A few slow ticks (autosaves, a big chunk load) are normal and the
	 * server catches up; only when more than 1 in 50 runs over is it really close to its limit.
	 */
	private static double slowTickShare(Breakdown b) {
		long slow = 0;
		long total = 0;
		int first = TickHistogram.binForMs(b.targetMspt);
		for (int i = 0; i < b.msptHistogram.length; i++) {
			total += b.msptHistogram[i];
			if (i >= first) {
				slow += b.msptHistogram[i];
			}
		}
		return total == 0 ? 0 : (double) slow / total;
	}

	// ── Data packs ──────────────────────────────────────────────────────────

	private static void datapacks(Breakdown b, List<Finding> out) {
		for (Views.PackView pack : Views.packs(b)) {
			if (pack.percent() < MIN_PERCENT || pack.functions().isEmpty()) {
				continue;
			}
			Views.FunctionView fn = pack.functions().get(0);
			StringBuilder reason = new StringBuilder();
			boolean direct = pack.id().equals(SourceIndex.COMMANDS.id());
			if (direct) {
				reason.append("Commands typed in chat/console or run by command blocks take ").append(ms(pack.ms())).append(".");
			} else {
				reason.append("Most of it is ").append(code(fn.id())).append(" (").append(ms(fn.ms()));
				if (fn.runsPerTick() > 0) {
					reason.append(", runs ").append(rate(fn.runsPerTick()));
				}
				if (fn.calledFrom() != null) {
					reason.append(", started from ").append(fn.calledFrom().toLowerCase(Locale.ROOT));
				}
				reason.append(").");
			}
			String hint = "Check whether these functions need to run every tick; /schedule them less often where possible.";
			if (!fn.lines().isEmpty()) {
				Views.LineView line = fn.lines().get(0);
				reason.append(" Heaviest line: ").append(code(shorten(line.text(), 90))).append(" with ").append(ms(line.ms()));
				if (line.perRun() >= 2) {
					reason.append(String.format(Locale.ROOT, ", about %s executions per run", count(line.perRun())));
				}
				reason.append('.');
				hint = lineHint(line);
			}
			String title = direct ? "Typed and command block commands" : pack.name();
			if (!direct && !pack.kind().equals("datapack")) {
				title = "Functions from " + pack.name();
			}
			out.add(new Finding(severity(pack.percent()), direct ? "Commands" : "Data pack", title, pack.ms(), pack.percent(), reason.toString(), hint));
		}
	}

	private static String lineHint(Views.LineView line) {
		String text = line.text().toLowerCase(Locale.ROOT);
		boolean allEntities = (text.contains("@e") && !text.contains("@e[")) || text.contains("@e[]");
		boolean unfiltered = text.contains("@e[") && !text.contains("type=") && !text.contains("tag=") && !text.contains("distance=")
				&& !text.contains("limit=");
		if (allEntities || unfiltered) {
			return "This line looks at every loaded entity. Add type=, tag=, distance= or limit= to the selector so fewer entities are checked.";
		}
		if (line.perRun() >= 50) {
			return "This line runs once for every matching entity. Narrow the selector, or spread the work over several ticks.";
		}
		if (text.contains("data modify") || text.contains("data get") || text.contains("data merge")) {
			return "Reading or writing NBT is slow, especially on entities and players. Scoreboards or tags are much cheaper.";
		}
		if (text.startsWith("fill ") || text.contains(" run fill ") || text.startsWith("clone ") || text.contains(" run clone ")) {
			return "Large fill or clone operations are expensive. Make the area smaller or run them less often.";
		}
		if (text.contains("predicate") || text.contains("loot ")) {
			return "Predicates and loot tables are evaluated every time this runs. Cache the result in a score or tag if you can.";
		}
		if (text.contains("summon ")) {
			return "Summoning entities is expensive and adds to entity load; check that this doesn't run more often than intended.";
		}
		return "Check whether this needs to run every tick. Running it every few ticks with /schedule often gives the same result.";
	}

	// ── Mods (sampler) ──────────────────────────────────────────────────────

	private static void mods(Breakdown b, List<Finding> out) {
		if (b.samples < MIN_SAMPLES) {
			return;
		}
		for (Views.ModView mod : Views.mods(b)) {
			if (mod.id().equals("minecraft") || mod.percent() < MIN_PERCENT) {
				continue;
			}
			boolean self = mod.id().equals("nicecontrolcenter");
			StringBuilder reason = new StringBuilder();
			reason.append(String.format(Locale.ROOT, "Its code was running in %.1f%% of tick samples", mod.selfPercent()));
			if (mod.causedPercent() >= 0.5) {
				reason.append(String.format(Locale.ROOT, ", and vanilla code it called in another %.1f%%", mod.causedPercent()));
			}
			reason.append('.');
			if (!mod.methods().isEmpty()) {
				reason.append(" Hottest: ").append(code(mod.methods().get(0).name())).append('.');
			}
			String hint = self
					? "This is Nice Control Center's own overhead. Raise sampler_interval_ms in the config to lower it."
					: "Look at this mod's config for heavy features, update it, or test the server without it to confirm. "
							+ "\"(mixin)\" means it changes vanilla code at that point.";
			out.add(new Finding(severity(mod.percent()), self ? "Monitor" : "Mod", mod.name(), mod.ms(), mod.percent(),
					reason.toString(), hint));
		}
	}

	// ── Entities / block entities ───────────────────────────────────────────

	private static void types(Breakdown b, List<Views.TypeView> rows, String category, List<Finding> out) {
		Map<String, Views.HotspotView> hotspotByType = new HashMap<>();
		for (Views.HotspotView hotspot : Views.hotspots(b)) {
			if (hotspot.topType() != null) {
				hotspotByType.putIfAbsent(hotspot.topType(), hotspot);
			}
		}
		for (Views.TypeView type : rows) {
			if (type.percent() < MIN_PERCENT) {
				continue;
			}
			String name = prettyType(type.id());
			StringBuilder reason = new StringBuilder();
			reason.append(String.format(Locale.ROOT, "%s loaded on average, %s per tick each",
					count(type.averageCount()), eachMs(type.microsEach() / 1000)));
			if (!type.source().equals("Minecraft")) {
				reason.append(" (added by ").append(type.source()).append(')');
			}
			reason.append('.');
			Views.HotspotView hotspot = hotspotByType.get(type.id());
			if (hotspot != null) {
				reason.append(String.format(Locale.ROOT, " %d of them are in one chunk near %d, %d in %s.",
						hotspot.topTypeCount(), hotspot.blockX(), hotspot.blockZ(), prettyDimension(hotspot.dimension())));
			}
			out.add(new Finding(severity(type.percent()), category, name, type.ms(), type.percent(), reason.toString(), typeHint(type)));
		}
	}

	private static String typeHint(Views.TypeView type) {
		String id = type.id();
		switch (id) {
			case "minecraft:item":
				return "Lots of dropped items, usually from farms or overflowing hoppers/chests. Collect them or add item cleanup.";
			case "minecraft:experience_orb":
				return "Many XP orbs, usually from a mob or furnace farm. Collect them more often.";
			case "minecraft:hopper":
				return "Hoppers check for items every tick. A solid block (like a composter) on top of idle hoppers, or fewer hoppers, helps.";
			case "minecraft:villager":
				return "Villagers are expensive (pathfinding, job and bed searching). Smaller trading halls or trapping them in 1x1 cells helps.";
			case "minecraft:armor_stand":
				return "Armor stands do collision checks every tick; markers or display entities are much cheaper for decoration.";
			default:
				break;
		}
		if (type.microsEach() >= 150) {
			return "Each one is expensive to tick (AI, pathfinding or collisions). Limit how many are loaded, or look for stuck mobs.";
		}
		if (type.averageCount() >= 300) {
			return "There are a lot of them. Look for farms, mob grinders or spawners in the busiest chunks below.";
		}
		return "Check the busiest chunks below to find where they are.";
	}

	// ── World phases ────────────────────────────────────────────────────────

	private static void world(Breakdown b, List<Finding> out) {
		Views.Kpi kpi = Views.kpi(b);
		for (Views.PhaseView phase : Views.phases(b)) {
			Phase p = Phase.valueOf(phase.phase());
			String reason;
			String hint;
			double threshold = 10;
			switch (p) {
				case CHUNKS -> {
					reason = String.format(Locale.ROOT, "%s chunks loaded, %s waiting chunk tasks. Includes random ticks (crop growth, leaf decay) and mob spawning.",
							count(kpi.counts().chunks), count(kpi.counts().chunkTasks));
					hint = kpi.counts().chunkTasks > 200
							? "Many chunks are being loaded or generated. Pre-generating the world (e.g. with Chunky) removes most of this."
							: "Lower simulation-distance, reduce chunk loaders, or pre-generate the world.";
					threshold = 15;
				}
				case SCHEDULED_TICKS -> {
					reason = String.format(Locale.ROOT, "%s block ticks and %s fluid ticks are queued.",
							count(kpi.counts().blockTicks), count(kpi.counts().fluidTicks));
					hint = "Lots of redstone, flowing water/lava or falling blocks. Look for running redstone clocks.";
				}
				case BLOCK_EVENTS -> {
					reason = "Pistons, note blocks and similar block events.";
					hint = "Usually a piston-based redstone contraption running non-stop.";
				}
				case ENTITY_MANAGEMENT -> {
					reason = "Entities being loaded and saved as chunks load and unload.";
					hint = "Players moving fast through the world, or chunks loading and unloading repeatedly at a border.";
				}
				case CONNECTION -> {
					reason = String.format(Locale.ROOT, "Handling packets from %d players.", kpi.counts().players);
					hint = "High with many players or mods that send lots of packets.";
				}
				case PLAYERS -> {
					reason = String.format(Locale.ROOT, "Ticking %d players.", kpi.counts().players);
					hint = "Grows with player count, large inventories and advancements with many criteria.";
				}
				case AUTOSAVE -> {
					reason = "Writing the world to disk.";
					hint = "Saving shows up as lag spikes. Faster storage, or fewer loaded chunks, helps.";
				}
				case WORLD -> {
					reason = "Weather, time, world border and scheduled functions.";
					hint = "If this is high, check for data packs that use /schedule a lot.";
				}
				default -> {
					continue;
				}
			}
			if (phase.percent() < threshold) {
				continue;
			}
			out.add(new Finding(severity(phase.percent()), "World", phase.label(), phase.ms(), phase.percent(), reason, hint));
		}
	}

	// ── World generation ────────────────────────────────────────────────────

	private static void worldgen(Breakdown b, List<Finding> out) {
		Views.WorldgenView w = Views.worldgen(b);
		if (w.chunks() < 20 || w.chunksPerMinute() < 20) {
			return;
		}
		Views.Kpi kpi = Views.kpi(b);
		StringBuilder reason = new StringBuilder(String.format(Locale.ROOT,
				"%s new chunks per minute, each taking %s of worker time.", count(w.chunksPerMinute()), eachMs(w.msPerChunk())));
		if (!w.stages().isEmpty()) {
			Views.TimingView stage = w.stages().get(0);
			reason.append(String.format(Locale.ROOT, " Slowest step: %s (%.0f%%).", stage.id().toLowerCase(Locale.ROOT), stage.percent()));
		}
		Views.TimingView biggest = null;
		for (Views.TimingView t : w.structures()) {
			biggest = t;
			break;
		}
		if (!w.features().isEmpty() && (biggest == null || w.features().get(0).msPerChunk() > biggest.msPerChunk())) {
			biggest = w.features().get(0);
		}
		if (biggest != null && biggest.percent() >= 5) {
			reason.append(" Biggest single cost: ").append(code(biggest.id())).append(" from ").append(biggest.source())
					.append(String.format(Locale.ROOT, " (%s per chunk).", eachMs(biggest.msPerChunk())));
		}
		boolean struggling = kpi.counts().chunkTasks > 200 || w.msPerChunk() > 40;
		out.add(new Finding(struggling ? Severity.WARN : Severity.INFO, "World generation",
				"New terrain is being generated", 0, 0, reason.toString(),
				"This runs on worker threads, so players wait for chunks rather than the tick slowing down. "
						+ "Pre-generating the world (e.g. with Chunky) removes it. If one data pack's structure or feature dominates, it may be worth simplifying."));

		for (Views.TimingView source : w.sources()) {
			if (source.id().equals("vanilla") || source.percent() < 25) {
				continue;
			}
			out.add(new Finding(Severity.INFO, "World generation", source.source() + " world generation", 0, 0,
					String.format(Locale.ROOT, "Its structures and features take %.0f%% of world generation time (%s per new chunk).",
							source.percent(), eachMs(source.msPerChunk())),
					"See the World generation section for which structures and features. Large or very frequent structures cost the most."));
			break;
		}
	}

	private static void explorers(Breakdown b, List<Finding> out) {
		Views.WorldgenView w = Views.worldgen(b);
		if (w.chunks() < 30) {
			return;
		}
		for (Views.PlayerView p : Views.players(b)) {
			if (p.newChunksPercent() >= 50 && p.newChunksPerMinute() >= 20) {
				out.add(new Finding(Severity.INFO, "Players", p.name() + " is generating most new terrain", 0, 0,
						String.format(Locale.ROOT, "%.0f%% of new chunks (%s per minute) were generated near %s, now at %d, %d in %s.",
								p.newChunksPercent(), count(p.newChunksPerMinute()), p.name(), p.x(), p.z(), prettyDimension(p.dimension())),
						"Usually exploring or flying with elytra. Pre-generating the world or a world border avoids this cost."));
			}
			break;
		}
	}

	// ── Redstone clocks and other busy blocks ───────────────────────────────

	private static void redstone(Breakdown b, List<Finding> out) {
		if (b.seconds < 30) {
			return;
		}
		int shown = 0;
		for (Views.BlockUpdateView u : Views.blockUpdates(b)) {
			if (shown >= 3 || u.perSecond() < 5 || u.activePercent() < 80) {
				continue;
			}
			shown++;
			String block = u.block() == null ? "" : u.block().replace("minecraft:", "");
			String kind;
			String hint;
			if (block.contains("piston")) {
				kind = "Piston machine";
				hint = "Pistons are among the most expensive blocks. If this is a farm, add an on/off switch.";
			} else if (block.contains("water") || block.contains("lava")) {
				kind = "Flowing liquid";
				hint = "Liquid that never settles keeps updating, often a broken water stream or lava flow. Check the area.";
			} else if (block.contains("repeater") || block.contains("comparator") || block.contains("observer")
					|| block.contains("redstone") || block.contains("hopper")) {
				kind = "Redstone clock";
				hint = "A clock that runs all the time costs tick time even when nobody uses it. Add a lever or an automatic stop.";
			} else {
				kind = "Constantly updating block";
				hint = "Something here updates non-stop. Look at the block and what is next to it.";
			}
			String reason = String.format(Locale.ROOT, "About %s block updates per second near %d, %d, %d in %s (%s), active %.0f%% of the time.",
					count(u.perSecond()), u.x(), u.y(), u.z(), prettyDimension(u.dimension()), block.isEmpty() ? "unknown block" : block.replace('_', ' '),
					u.activePercent());
			out.add(new Finding(u.perSecond() >= 40 ? Severity.WARN : Severity.INFO, "Redstone",
					kind + " at " + u.x() + ", " + u.y() + ", " + u.z(), 0, 0, reason, hint));
		}
	}

	// ── Spikes, memory, CPU ─────────────────────────────────────────────────

	/**
	 * Work the server thread did between ticks: player packets, chunk loading results, tasks mods queue. It's
	 * not in the tick times, but it delays the next tick, so TPS drops while every tick looks quick.
	 */
	private static void between(Breakdown b, List<Finding> out) {
		Views.BetweenView v = Views.between(b);
		if (v.busyPercent() < 5) {
			return;
		}
		Views.Kpi kpi = Views.kpi(b);
		boolean lagging = kpi.tps() < kpi.targetTps() * 0.95;
		// The same share of the second as ms in a tick, so it sorts and reads like the other findings.
		double perTick = v.busyMsPerSecond() / Math.max(1, kpi.targetTps());
		StringBuilder reason = new StringBuilder(String.format(Locale.ROOT,
				"The server thread worked %.0f ms of every second between ticks, next to %.0f ms of ticks; it waited %.0f ms.",
				v.busyMsPerSecond(), v.tickMsPerSecond(), v.idleMsPerSecond()));
		if (lagging) {
			reason.append(" That time isn't in the tick times, but the next tick waits for it, so TPS drops while ticks look quick.");
		}
		List<String> top = new ArrayList<>();
		for (Views.BetweenTaskView t : v.tasks()) {
			if (top.size() == 3 || t.percent() < 5) {
				break;
			}
			top.add(String.format(Locale.ROOT, "%s %s%s (%.0f%%)", t.label().toLowerCase(Locale.ROOT), code(t.task()),
					t.mod() == null ? "" : " from " + t.mod(), t.percent()));
		}
		if (!top.isEmpty()) {
			reason.append(" Mostly ").append(String.join(", ", top)).append('.');
			// Where inside the biggest task the time went (older recordings don't have this).
			Views.BetweenTaskView first = v.tasks().get(0);
			if (first.paths() != null && !first.paths().isEmpty() && first.paths().get(0).percent() >= 10) {
				Views.MethodView path = first.paths().get(0);
				reason.append(String.format(Locale.ROOT, " Inside %s mostly %s (%.0f%%).", code(first.task()), code(path.name()), path.percent()));
			}
		}
		String kind = v.tasks().isEmpty() ? "" : v.tasks().get(0).kind();
		String hint = switch (kind) {
			case eu.explorerseden.nicecontrolcenter.sampler.StackSampler.PACKETS ->
					"Handling what players send. Lots of movement, clicking or commands cost time here; check mods that send or answer many packets.";
			case eu.explorerseden.nicecontrolcenter.sampler.StackSampler.CHUNKS ->
					"Chunk loading and generation results are finished on the server thread. Pre-generate the world, and check mods that load chunks on their own (LOD mods like Distant Horizons, chunk loaders).";
			case eu.explorerseden.nicecontrolcenter.sampler.StackSampler.MOD ->
					"A mod runs work on the server thread outside the tick. Check its config, or test the server without it to confirm.";
			default -> "See \"Between ticks\" for what the server was doing.";
		};
		Severity severity = !lagging ? Severity.INFO : v.busyPercent() >= 30 ? Severity.POOR : Severity.WARN;
		out.add(new Finding(severity, "Between ticks", "Work between ticks", perTick, Views.percentOf(b, perTick), reason.toString(), hint));
	}

	private static final String STRETCHES = "Lag stretches";
	/**
	 * A minute counts when ticks plus the work between them took this much of each second, on average. Busy evenings
	 * with a few players sit around 50 %; from about two thirds on there's little room left before ticks run late.
	 */
	private static final double BUSY_MS_PER_SECOND = 650;
	/** A minute this busy makes the stretch a warning (with TPS under 95 % in a minute). */
	private static final double WARN_BUSY_MS_PER_SECOND = 800;

	/**
	 * Minutes where TPS dropped or the server thread was busy for most of each second (ticks plus work between them),
	 * merged into stretches. A long window's averages can hide an hour of trouble near its end; this names it.
	 */
	private static void stretches(Breakdown b, List<Point> points, List<Finding> out) {
		if (points == null || points.size() < 180) {
			return;
		}
		double target = 1000.0 / Math.max(1e-3, b.targetMspt);
		// Per minute: tps sum, tick ms sum, between ms sum, count.
		java.util.TreeMap<Long, double[]> minutes = new java.util.TreeMap<>();
		for (Point p : points) {
			if (p.paused()) {
				continue;
			}
			double[] m = minutes.computeIfAbsent(p.t() / 60_000, k -> new double[4]);
			m[0] += p.tps();
			m[1] += p.tickMs();
			m[2] += p.betweenMs();
			m[3]++;
		}
		// Stretch: first minute, last minute, bad minutes, tick ms, between ms, lowest minute tps, busiest minute.
		List<double[]> stretches = new ArrayList<>();
		double[] current = null;
		// The other minutes, as a baseline: whichever grew in the stretches is the cause.
		double normalTick = 0, normalBetween = 0, normalMinutes = 0;
		for (Map.Entry<Long, double[]> e : minutes.entrySet()) {
			double[] m = e.getValue();
			if (m[3] < 10) {
				continue;
			}
			double tps = m[0] / m[3];
			double tick = m[1] / m[3];
			double between = m[2] / m[3];
			if (tps >= target * 0.95 && tick + between < BUSY_MS_PER_SECOND) {
				normalTick += tick;
				normalBetween += between;
				normalMinutes++;
				continue;
			}
			long minute = e.getKey();
			if (current == null || minute - current[1] > 4) {
				current = new double[] { minute, minute, 0, 0, 0, Double.MAX_VALUE, 0 };
				stretches.add(current);
			}
			current[1] = minute;
			current[2]++;
			current[3] += tick;
			current[4] += between;
			// The worst minute's average: a single second with one long tick would read as TPS 1.
			current[5] = Math.min(current[5], tps);
			current[6] = Math.max(current[6], tick + between);
		}
		stretches.removeIf(s -> s[2] < 3);
		if (normalMinutes > 0) {
			normalTick /= normalMinutes;
			normalBetween /= normalMinutes;
		}
		if (stretches.isEmpty()) {
			return;
		}
		stretches.sort(Comparator.comparingDouble((double[] s) -> s[2]).reversed());
		java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());
		List<String> parts = new ArrayList<>();
		double tick = 0, between = 0, bad = 0, lowest = Double.MAX_VALUE, busiest = 0;
		for (double[] s : stretches) {
			if (parts.size() < 3) {
				parts.add(clock.format(java.time.Instant.ofEpochMilli((long) s[0] * 60_000)) + "–"
						+ clock.format(java.time.Instant.ofEpochMilli((long) (s[1] + 1) * 60_000)));
			}
			bad += s[2];
			tick += s[3];
			between += s[4];
			lowest = Math.min(lowest, s[5]);
			busiest = Math.max(busiest, s[6]);
		}
		tick /= bad;
		between /= bad;
		String reason = String.format(Locale.ROOT,
				"%d %s with %.0f bad minutes (%s): the server thread was busy %.0f ms of every second (ticks %.0f ms, work between ticks %.0f ms), and TPS averaged %.1f in the worst minute. Otherwise: ticks %.0f ms, work between ticks %.0f ms.",
				stretches.size(), stretches.size() == 1 ? "stretch" : "stretches", bad, String.join(", ", parts), tick + between, tick, between, lowest,
				normalTick, normalBetween);
		String hint = between - normalBetween > tick - normalTick
				? "Mostly more work between ticks, which the tick times don't show. See \"Between ticks\" for what it was, and check what players were doing then."
				: "The ticks themselves got slower. See what costs the most above, or record a report while it happens.";
		// A warning only when TPS really dropped for a minute, or a minute left almost no room.
		Severity severity = lowest < target * 0.95 || busiest >= WARN_BUSY_MS_PER_SECOND ? Severity.WARN : Severity.INFO;
		out.add(new Finding(severity, STRETCHES, "Stretches close to the limit", 0, 0, reason, hint));
	}

	private static void spikes(Breakdown b, List<Finding> out) {
		// The same cut-off as the spike list (spike_threshold_ms), so the count matches what's listed.
		int threshold = (int) Math.ceil(eu.explorerseden.nicecontrolcenter.core.Tracker.spikeThresholdNs / 1e6);
		int slow = 0;
		for (int i = TickHistogram.binForMs(threshold); i < b.msptHistogram.length; i++) {
			slow += b.msptHistogram[i];
		}
		if (slow == 0) {
			return;
		}
		Map<String, Integer> causes = new HashMap<>();
		for (Breakdown.Spike spike : b.spikes) {
			if (!spike.phases.isEmpty()) {
				causes.merge(spike.phases.get(0).name, 1, Integer::sum);
			}
		}
		String cause = causes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		String reason = String.format(Locale.ROOT, "%d %s took longer than %d ms; the worst took %.0f ms.", slow, slow == 1 ? "tick" : "ticks",
				threshold, b.tickNsMax / 1e6);
		if (cause != null) {
			reason += " Most were caused by " + cause.toLowerCase(Locale.ROOT) + ".";
		}
		String hint = switch (cause == null ? "" : cause) {
			case "Autosave" -> "Saving causes the spikes. Switch on Smooth saving (Tuning), and fewer loaded chunks help.";
			case "Network & console", "World upkeep" -> "Often teleports: /tp and /spreadplayers (random teleports) load the target chunk on the spot, "
					+ "and generate it if nobody has been there yet; spreadplayers rolls again on water or lava and generates another. "
					+ "Pre-generate the area random teleports reach, and keep Warm teleport targets on for hubs and homes.";
			case "Chunk management" -> "Chunk loading/generation causes the spikes. Pre-generate the world.";
			case "Data pack functions" -> "A data pack does a lot of work in single ticks. See the data pack findings above.";
			case "Other" -> "Often garbage collection pauses or a heavy command. Check memory below.";
			default -> "Players notice these as rubber-banding. See the spike list for what happened in each.";
		};
		Severity severity = slow > b.seconds / 10 ? Severity.WARN : Severity.INFO;
		out.add(new Finding(severity, "Lag spikes", "Lag spikes", 0, 0, reason, hint));
	}

	private static void memory(Breakdown b, List<Finding> out) {
		Views.Kpi kpi = Views.kpi(b);
		if (kpi.heapMax() <= 0) {
			return;
		}
		// Judge by memory still in use after garbage collection. The raw "used" value includes garbage
		// that simply hasn't been collected yet and is normally close to the maximum.
		double livePercent = kpi.heapLiveMax() * 100.0 / kpi.heapMax();
		double gcPercent = kpi.gcPercent();
		if (livePercent < 85 && gcPercent < 5) {
			return;
		}
		// Concurrent collectors (ZGC) barely pause; when the heap is nearly full they run all the time instead.
		String background = kpi.gcBackgroundPercent() >= 50
				? String.format(Locale.ROOT, " The collector ran in the background %.0f%% of the time; with a nearly full heap the server waits for it to free memory, which no pause counter shows.",
						Math.min(100, kpi.gcBackgroundPercent()))
				: "";
		String reason = String.format(Locale.ROOT,
				"After garbage collection, %s of %s is still in use (%.0f%%); GC pauses took %.1f%% of the time.%s",
				bytes(kpi.heapLiveMax()), bytes(kpi.heapMax()), livePercent, gcPercent, background);
		Severity severity = gcPercent >= 10 || livePercent >= 95 ? Severity.POOR : Severity.WARN;
		out.add(new Finding(severity, "Memory", "Memory pressure", 0, 0, reason,
				"The server really needs most of its memory. Give it more (-Xmx), or reduce loaded chunks and entities. "
						+ "Little free memory left after collections leads to frequent GC pauses, which players notice as lag spikes."));
	}

	/**
	 * Memory still in use after garbage collection that keeps rising while the world stays the same (same players,
	 * about the same loaded chunks) for at least half an hour: something may be holding on to memory.
	 */
	private static void memoryGrowth(List<Point> points, List<Finding> out) {
		if (points == null || points.size() < 1800) {
			return;
		}
		long bestGrowth = 0;
		long from = 0, to = 0, fromBytes = 0, toBytes = 0;
		int start = -1;
		for (int i = 0; i <= points.size(); i++) {
			Point p = i < points.size() ? points.get(i) : null;
			boolean usable = p != null && !p.paused() && p.heapLive() > 0;
			if (start >= 0 && usable) {
				Point first = points.get(start);
				boolean same = p.players() == first.players() && Math.abs(p.chunks() - first.chunks()) <= first.chunks() * 0.1 + 16;
				if (same) {
					continue;
				}
			}
			if (start >= 0) {
				// The segment [start, i) had the same world; compare its first and last five minutes.
				List<Point> seg = points.subList(start, i);
				long span = seg.getLast().t() - seg.getFirst().t();
				if (span >= 30 * 60_000L) {
					long head = averageLive(seg, seg.getFirst().t(), seg.getFirst().t() + 5 * 60_000L);
					long tail = averageLive(seg, seg.getLast().t() - 5 * 60_000L, seg.getLast().t());
					if (tail - head > bestGrowth) {
						bestGrowth = tail - head;
						from = seg.getFirst().t();
						to = seg.getLast().t();
						fromBytes = head;
						toBytes = tail;
					}
				}
			}
			start = usable ? i : -1;
		}
		if (bestGrowth < 1L << 30 || toBytes < fromBytes * 1.4) {
			return;
		}
		java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());
		String reason = String.format(Locale.ROOT,
				"From %s to %s the same players had about the same chunks loaded, but memory still in use after garbage collection rose from %s to %s.",
				clock.format(java.time.Instant.ofEpochMilli(from)), clock.format(java.time.Instant.ofEpochMilli(to)), bytes(fromBytes), bytes(toBytes));
		out.add(new Finding(Severity.INFO, "Memory", "Memory in use keeps growing", 0, 0, reason,
				"Can be normal (caches filling up), but if it keeps rising over hours, something holds on to memory. "
						+ "Run \"jcmd <pid> GC.class_histogram\" twice, half an hour apart, and compare which classes grow."));
	}

	private static long averageLive(List<Point> points, long from, long to) {
		long sum = 0;
		int n = 0;
		for (Point p : points) {
			if (p.t() >= from && p.t() <= to && p.heapLive() > 0) {
				sum += p.heapLive();
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/**
	 * The server thread was busy (in a tick or working between ticks) but not running on a core: blocked on a
	 * lock, waiting for another thread or the disk, or not given a core by the host. Says which it was.
	 */
	private static void offCpu(Breakdown b, List<Finding> out) {
		Views.BetweenView v = Views.between(b);
		double busy = v.tickMsPerSecond() + v.busyMsPerSecond();
		double off = v.offCpuMsPerSecond();
		if (busy <= 0 || off < 50 || off / busy < 0.2) {
			return;
		}
		Views.Kpi kpi = Views.kpi(b);
		boolean lagging = kpi.tps() < kpi.targetTps() * 0.95;
		StringBuilder reason = new StringBuilder(String.format(Locale.ROOT,
				"Of %.0f ms the server thread was busy each second, it spent %.0f ms (%.0f%%) not running: %.0f ms in ticks, %.0f ms between them.",
				busy, off, off * 100 / busy, v.tickOffCpuMsPerSecond(), v.betweenOffCpuMsPerSecond()));
		List<Views.WaitView> waits = Views.waits(b);
		List<String> top = new ArrayList<>();
		for (Views.WaitView w : waits) {
			if (top.size() == 3 || w.msPerSecond() < 5) break;
			top.add(String.format(Locale.ROOT, "%s (%.0f ms/s, %s, at %s%s)", w.what(), w.msPerSecond(), w.where().toLowerCase(Locale.ROOT),
					code(w.frame()), w.mod() == null ? "" : " from " + w.mod()));
		}
		if (!top.isEmpty()) {
			reason.append(" Seen waiting for: ").append(String.join("; ", top)).append('.');
		}
		Views.HostView h = Views.host(b);
		boolean hostCpu = h.stealPercent() >= 5 || h.throttledMsPerSecond() >= 20;
		boolean disk = h.iowaitPercent() >= 10 || h.psiIo() >= 10 || !waits.isEmpty() && waits.get(0).what().equals("disk I/O");
		String hint;
		if (hostCpu) {
			hint = "The host takes CPU time away (see Machine & threads). That can't be fixed in the game: give the container more CPU or move to a plan without shared cores.";
		} else if (disk) {
			hint = "The server waits for the disk. Faster storage (local SSD, no network drive) helps; check what writes a lot at the same time (backups, map renders, Distant Horizons).";
		} else if (!top.isEmpty()) {
			hint = "The thread waits for another thread or a lock. The mod or thread named above is where to look: its settings, or test without it.";
		} else {
			hint = "Not running without a clear reason seen: often the host scheduling other work first. Compare with CPU steal and pressure in Machine & threads.";
		}
		out.add(new Finding(lagging ? (off / busy >= 0.4 ? Severity.POOR : Severity.WARN) : Severity.INFO, "Server thread",
				"The server thread is waiting, not working", off / Math.max(1, kpi.targetTps()), Views.percentOf(b, off / Math.max(1, kpi.targetTps())),
				reason.toString(), hint));
	}

	/** CPU taken away by the host, a slow disk, or a thread group that uses whole cores. */
	private static void host(Breakdown b, List<Finding> out) {
		Views.HostView h = Views.host(b);
		if (h.stealPercent() >= 5 || h.throttledMsPerSecond() >= 20) {
			StringBuilder reason = new StringBuilder();
			if (h.stealPercent() >= 5) {
				reason.append(String.format(Locale.ROOT, "The virtual machine's host took %.1f%% of the CPU time (steal). ", h.stealPercent()));
			}
			if (h.throttledMsPerSecond() >= 20) {
				reason.append(String.format(Locale.ROOT, "The container's CPU limit%s held the server back %.0f ms of every second (%.0f%% of scheduling periods). ",
						h.cpuLimitCores() > 0 ? String.format(Locale.ROOT, " (%.1f cores)", h.cpuLimitCores()) : "", h.throttledMsPerSecond(), h.throttledPercent()));
			}
			out.add(new Finding(Severity.WARN, "Host", "The host takes CPU away from the server", 0, 0, reason.toString().trim(),
					"No game setting fixes this. Raise or remove the container's CPU limit (Docker/Portainer), or use a server plan with dedicated cores."));
		}
		if (h.psiIo() >= 10 || h.iowaitPercent() >= 10) {
			out.add(new Finding(Severity.WARN, "Host", "The disk is slow to answer", 0, 0,
					String.format(Locale.ROOT, "Tasks waited for the disk %.0f%% of the time (pressure)%s.", Math.max(0, h.psiIo()),
							h.iowaitPercent() >= 0 ? String.format(Locale.ROOT, ", I/O wait %.1f%%", h.iowaitPercent()) : ""),
					"Saving, chunk loading and backups all wait for it. Use local SSD storage and avoid backups or renders during play."));
		}
		int cores = eu.explorerseden.nicecontrolcenter.core.SystemMetrics.cores();
		for (Views.ThreadView t : h.threads()) {
			if (t.name().equals("Server thread") || t.cores() < 0.9) continue;
			out.add(new Finding(Severity.INFO, "Threads", t.name() + " uses " + String.format(Locale.ROOT, "%.1f", t.cores()) + " cores", 0, 0,
					String.format(Locale.ROOT, "%d thread%s named like this used %.1f of the %d cores on average.", t.threads(), t.threads() == 1 ? "" : "s", t.cores(), cores),
					"Fine while cores are free; with a CPU limit or few cores it competes with the server thread."));
		}
	}

	private static void cpu(Breakdown b, List<Finding> out) {
		Views.Kpi kpi = Views.kpi(b);
		if (kpi.cpuSystem() >= 90 && kpi.cpuProcess() < kpi.cpuSystem() * 0.6) {
			out.add(new Finding(Severity.WARN, "CPU", "The machine is busy with something else",
					0, 0, String.format(Locale.ROOT, "System CPU is at %.0f%%, but the server itself only uses %.0f%%.", kpi.cpuSystem(), kpi.cpuProcess()),
					"Other programs or servers on the same machine take CPU time away from Minecraft."));
		}
	}

	// ── Formatting ──────────────────────────────────────────────────────────

	static Severity severity(double percent) {
		if (percent >= 30) {
			return Severity.POOR;
		}
		if (percent >= 10) {
			return Severity.WARN;
		}
		return Severity.INFO;
	}

	public static String ms(double ms) {
		return ms >= 10 ? String.format(Locale.ROOT, "%.1f ms/tick", ms) : String.format(Locale.ROOT, "%.2f ms/tick", ms);
	}

	/** Small per-item times in ms, e.g. "0.07 ms". */
	public static String eachMs(double ms) {
		if (ms >= 1) {
			return String.format(Locale.ROOT, "%.1f ms", ms);
		}
		return ms >= 0.01 ? String.format(Locale.ROOT, "%.2f ms", ms) : "<0.01 ms";
	}

	public static String count(double value) {
		if (value >= 10_000) {
			return String.format(Locale.ROOT, "%.0fk", value / 1000);
		}
		return value >= 10 || value == Math.rint(value) ? String.format(Locale.ROOT, "%,.0f", value) : String.format(Locale.ROOT, "%.1f", value);
	}

	static String rate(double perTick) {
		if (perTick >= 0.95) {
			return count(perTick) + "× per tick";
		}
		double perSecond = perTick * 20;
		return perSecond >= 1 ? count(perSecond) + "× per second" : "occasionally";
	}

	public static String bytes(long bytes) {
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
		}
		double mb = bytes / 1024.0 / 1024.0;
		return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024) : String.format(Locale.ROOT, "%.0f MB", mb);
	}

	static String code(String text) {
		return "`" + text + "`";
	}

	static String quote(String text) {
		return "\"" + text + "\"";
	}

	static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	public static String prettyType(String id) {
		String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
		String name = path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
		return id.startsWith("minecraft:") ? name : name + " (" + id.substring(0, id.indexOf(':')) + ")";
	}

	public static String prettyDimension(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "the Overworld";
			case "minecraft:the_nether" -> "the Nether";
			case "minecraft:the_end" -> "the End";
			default -> dimension;
		};
	}
}
