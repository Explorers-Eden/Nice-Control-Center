package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.gamerules.GameRules;

import eu.explorerseden.nicecontrolcenter.core.SystemMetrics;

/**
 * Checks settings that commonly hurt performance: server properties, gamerules, JVM/GC and
 * missing optimisation mods. Runs on the server thread at start and every few minutes.
 */
public final class Advisor {
	/** One checked setting: its value, a verdict, and a short explanation. */
	public record Check(String group, String name, String value, Diagnoser.Severity status, String note) {
	}

	private static final long INTERVAL_MS = 60_000L;

	private static volatile List<Check> checks = List.of();
	private static long lastRun;

	private Advisor() {
	}

	public static List<Check> checks() {
		return checks;
	}

	public static void reset() {
		lastRun = 0;
		checks = List.of();
	}

	/** Re-checks if the last run is older than the interval. Server thread. */
	public static void maybeRun(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (now - lastRun < INTERVAL_MS) {
			return;
		}
		lastRun = now;
		try {
			checks = run(server);
		} catch (RuntimeException e) {
			// A missing value on an unusual server type shouldn't break monitoring.
			checks = List.of();
		}
	}

	private static List<Check> run(MinecraftServer server) {
		List<Check> result = new ArrayList<>();
		Diagnoser.Severity good = Diagnoser.Severity.GOOD;
		Diagnoser.Severity info = Diagnoser.Severity.INFO;
		Diagnoser.Severity warn = Diagnoser.Severity.WARN;

		// Distances
		int view = server.getPlayerList().getViewDistance();
		int simulation = server.getPlayerList().getSimulationDistance();
		result.add(new Check("Server", "View distance", String.valueOf(view), view > 12 ? warn : good,
				view > 12 ? "Each step adds a ring of chunks for every player. 8–12 is common on busy servers." : "Fine."));
		result.add(new Check("Server", "Simulation distance", String.valueOf(simulation), simulation > 10 ? warn : good,
				simulation > 10 ? "Entities and blocks tick this far from players. 6–8 saves a lot on servers with farms." : "Fine."));

		if (server instanceof DedicatedServer dedicated) {
			DedicatedServerProperties props = dedicated.getProperties();
			boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
			result.add(new Check("Server", "sync-chunk-writes", String.valueOf(props.syncChunkWrites),
					props.syncChunkWrites && !windows ? info : good,
					props.syncChunkWrites && !windows ? "Saving waits for every chunk write. Setting it to false makes saving faster on Linux." : "Fine."));
			int pause = props.pauseWhenEmptySeconds.get();
			result.add(new Check("Server", "pause-when-empty-seconds", String.valueOf(pause), pause <= 0 ? info : good,
					pause <= 0 ? "The world keeps ticking while nobody is online. A value like 60 saves CPU (but stops farms then)." : "Fine."));
			int broadcast = props.entityBroadcastRangePercentage.get();
			result.add(new Check("Server", "entity-broadcast-range-percentage", broadcast + "%", broadcast > 100 ? info : good,
					broadcast > 100 ? "Players receive entities from further away, which costs network and client performance." : "Fine."));
			int compression = props.networkCompressionThreshold;
			result.add(new Check("Server", "network-compression-threshold", String.valueOf(compression),
					compression >= 0 && compression < 64 ? info : good,
					compression >= 0 && compression < 64 ? "Very small packets get compressed too, which costs CPU. 256 is the default." : "Fine."));
		}

		// Gamerules
		GameRules rules = server.overworld().getGameRules();
		int randomTick = rules.get(GameRules.RANDOM_TICK_SPEED);
		result.add(new Check("Gamerules", "random_tick_speed", String.valueOf(randomTick), randomTick > 3 ? warn : good,
				randomTick > 3 ? "Crops, leaves and many blocks tick " + (randomTick / 3) + "× as often as normal. Default is 3." : "Fine."));
		int cramming = rules.get(GameRules.MAX_ENTITY_CRAMMING);
		result.add(new Check("Gamerules", "max_entity_cramming", String.valueOf(cramming), cramming == 0 || cramming > 24 ? info : good,
				cramming == 0 || cramming > 24 ? "Mobs can pile up without limit in one spot, which is expensive. Default is 24." : "Fine."));

		// JVM
		long maxHeap = Runtime.getRuntime().maxMemory();
		List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
		boolean xmsSet = args.stream().anyMatch(a -> a.startsWith("-Xms"));
		String gc = garbageCollector();
		result.add(new Check("Java", "Java version", Runtime.version().toString(), good, "Fine."));
		result.add(new Check("Java", "Max memory (-Xmx)", Diagnoser.bytes(maxHeap), maxHeap < 3L << 30 ? warn : maxHeap > 16L << 30 ? info : good,
				maxHeap < 3L << 30 ? "Less than 3 GB is tight for a modded server; 4–8 GB is typical."
						: maxHeap > 16L << 30 ? "More than 16 GB rarely helps and can make garbage collection pauses longer." : "Fine."));
		result.add(new Check("Java", "-Xms", xmsSet ? "set" : "not set", xmsSet ? good : info,
				xmsSet ? "Fine." : "Setting -Xms to the same value as -Xmx avoids the heap growing and shrinking while running."));
		boolean goodGc = gc.contains("G1") || gc.contains("ZGC") || gc.contains("Shenandoah");
		result.add(new Check("Java", "Garbage collector", gc, goodGc ? good : warn,
				goodGc ? "Fine." : "G1 (with Aikar's flags) or ZGC give much shorter pauses than this collector."));
		int cores = SystemMetrics.cores();
		result.add(new Check("Java", "CPU cores", String.valueOf(cores), cores <= 2 ? warn : good,
				cores <= 2 ? "With 2 or fewer cores, world generation, saving and the game compete for CPU." : "Fine."));

		return result;
	}

	private static String garbageCollector() {
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			String name = bean.getName();
			if (name.contains("G1")) {
				return "G1";
			}
			if (name.contains("ZGC")) {
				return "ZGC";
			}
			if (name.contains("Shenandoah")) {
				return "Shenandoah";
			}
			if (name.contains("PS ") || name.contains("Parallel")) {
				return "Parallel";
			}
			if (name.equals("Copy") || name.contains("MarkSweepCompact")) {
				return "Serial";
			}
		}
		return "unknown";
	}

	/** Settings worth acting on, as findings: every warning. */
	static void addFindings(List<Diagnoser.Finding> out) {
		for (Check check : checks) {
			if (check.status() == Diagnoser.Severity.WARN) {
				out.add(new Diagnoser.Finding(check.status(), "Settings", check.name() + ": " + check.value(), 0, 0,
						"Currently " + check.value() + ".", check.note()));
			}
		}
	}
}
