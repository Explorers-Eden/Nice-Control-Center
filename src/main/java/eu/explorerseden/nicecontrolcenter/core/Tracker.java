package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.mixin.MinecraftServerAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Times what the server thread does during a tick.
 *
 * <p>Every timed piece of work (a tick phase, one entity's tick, one function command, ...) is a
 * frame on a small stack. When a frame ends, its self time (its own time minus nested frames) goes
 * to its {@link Stat}, and also to the phase it ran in. Self times never overlap, so everything
 * adds up to the real tick time.
 *
 * <p>All methods except {@link #isActive()} must only be called from the server thread, which
 * mixins check with {@link #isActive()} first. No locks or allocations on the hot path.
 */
public final class Tracker {
	public static final String SERVER_DIMENSION = "server";
	private static final int MAX_DEPTH = 1024;

	private static volatile Thread serverThread;
	private static volatile boolean inTick;
	/** Whether timing is on; changes are applied at the next tick start so frames always pair up. */
	private static volatile boolean wantEnabled = true;
	private static volatile boolean enabled = true;
	private static MinecraftServer server;

	private static final long[] start = new long[MAX_DEPTH];
	private static final long[] child = new long[MAX_DEPTH];
	private static final PhaseKey[] phaseOf = new PhaseKey[MAX_DEPTH];
	private static final PhaseKey[] previousPhase = new PhaseKey[MAX_DEPTH];
	private static int depth;
	private static PhaseKey currentPhase;

	/** Self time of the frame that ended last, for callers that split it further (function lines). */
	public static long lastSelfNs;
	/** Total time of the frame that ended last (chunk hot spots). */
	public static long lastElapsedNs;

	private static long tickStart;
	/** Stats that got time in the current tick, to name the culprits of a lag spike. */
	private static int tickId;
	private static Stat[] touched = new Stat[256];
	private static int touchedCount;
	private static final long[] tickPhaseNs = new long[Phase.VALUES.length];
	private static final Map<ServerLevel, PhaseKey[]> levelPhases = new IdentityHashMap<>();

	public static LiveBucket bucket = new LiveBucket();
	public static long spikeThresholdNs = 100_000_000L;

	private Tracker() {
	}

	public static void start(MinecraftServer minecraftServer) {
		server = minecraftServer;
		bucket = new LiveBucket();
		levelPhases.clear();
		depth = 0;
		currentPhase = null;
		serverThread = minecraftServer.getRunningThread();
	}

	public static void stop() {
		serverThread = null;
		inTick = false;
		server = null;
		levelPhases.clear();
	}

	public static MinecraftServer server() {
		return server;
	}

	/** True when called on the server thread while monitoring runs. */
	public static boolean isActive() {
		return enabled && serverThread == Thread.currentThread();
	}

	/** True on the server thread, whether or not monitoring is switched on. */
	public static boolean isServerThread() {
		return serverThread == Thread.currentThread();
	}

	public static boolean enabled() {
		return wantEnabled;
	}

	/** Switches monitoring on or off from any thread; takes effect at the next tick. */
	public static void setEnabled(boolean on) {
		wantEnabled = on;
	}

	/** True while the server thread is inside a tick, read by the sampler thread. */
	public static boolean inTick() {
		return inTick;
	}

	public static Thread serverThread() {
		return serverThread;
	}

	// ── Frames ──────────────────────────────────────────────────────────────

	public static void enter() {
		int d = depth++;
		if (d < MAX_DEPTH) {
			child[d] = 0;
			phaseOf[d] = currentPhase;
			previousPhase[d] = currentPhase;
			start[d] = System.nanoTime();
		}
	}

	public static void enterPhase(PhaseKey phase) {
		int d = depth++;
		if (d < MAX_DEPTH) {
			child[d] = 0;
			phaseOf[d] = phase;
			previousPhase[d] = currentPhase;
			currentPhase = phase;
			start[d] = System.nanoTime();
		}
	}

	/** Ends the innermost frame and charges it to {@code stat} (may be null). */
	public static void exit(Stat stat) {
		long now = System.nanoTime();
		int d = --depth;
		if (d < 0) {
			depth = 0;
			lastSelfNs = 0;
			lastElapsedNs = 0;
			return;
		}
		if (d >= MAX_DEPTH) {
			lastSelfNs = 0;
			lastElapsedNs = 0;
			return;
		}
		long elapsed = now - start[d];
		long self = elapsed - child[d];
		if (d > 0) {
			child[d - 1] += elapsed;
		}
		if (stat != null) {
			stat.selfNs += self;
			stat.totalNs += elapsed;
			stat.calls++;
			if (stat.tickId != tickId) {
				stat.tickId = tickId;
				stat.tickNs = 0;
				if (touchedCount == touched.length && touched.length < 16_384) {
					touched = Arrays.copyOf(touched, touched.length * 2);
				}
				if (touchedCount < touched.length) {
					touched[touchedCount++] = stat;
				}
			}
			stat.tickNs += self;
		}
		PhaseKey phase = phaseOf[d];
		if (phase != null) {
			bucket.phase(phase).selfNs += self;
			tickPhaseNs[phase.phase().ordinal()] += self;
		}
		currentPhase = previousPhase[d];
		lastSelfNs = self;
		lastElapsedNs = elapsed;
	}

	public static void exitPhase() {
		exit(null);
	}

	/** The phase the server thread is currently in, or null outside any phase. */
	public static Phase currentPhase() {
		return currentPhase == null ? null : currentPhase.phase();
	}

	public static PhaseKey phase(ServerLevel level, Phase phase) {
		PhaseKey[] keys = levelPhases.get(level);
		if (keys == null) {
			keys = keysFor(level.dimension().identifier().toString());
			levelPhases.put(level, keys);
		}
		return keys[phase.ordinal()];
	}

	public static PhaseKey serverPhase(Phase phase) {
		return serverPhases[phase.ordinal()];
	}

	public static void countBlockUpdate(ServerLevel level, net.minecraft.core.BlockPos pos) {
		bucket.addBlockUpdate(dimension(level), pos.asLong());
	}

	public static String dimension(ServerLevel level) {
		return phase(level, Phase.WORLD).dimension();
	}

	private static int nextPhaseIndex;
	private static final PhaseKey[] serverPhases = keysFor(SERVER_DIMENSION);

	private static synchronized PhaseKey[] keysFor(String dimension) {
		PhaseKey[] keys = new PhaseKey[Phase.VALUES.length];
		for (Phase phase : Phase.VALUES) {
			keys[phase.ordinal()] = new PhaseKey(dimension, phase, nextPhaseIndex++);
		}
		return keys;
	}

	// ── Ticks ───────────────────────────────────────────────────────────────

	/** Applies a pending on/off switch. Returns whether this tick is monitored. */
	public static boolean prepareTick() {
		if (enabled != wantEnabled) {
			enabled = wantEnabled;
			bucket = new LiveBucket();
			depth = 0;
			currentPhase = null;
		}
		return enabled;
	}

	public static void beginTick() {
		tickId++;
		Arrays.fill(touched, 0, touchedCount, null);
		touchedCount = 0;
		depth = 0;
		currentPhase = null;
		Arrays.fill(tickPhaseNs, 0);
		inTick = true;
		tickStart = System.nanoTime();
	}

	public static void endTick() {
		long tickNs = System.nanoTime() - tickStart;
		inTick = false;
		depth = 0;
		currentPhase = null;

		LiveBucket b = bucket;
		if (isPausedWhenEmpty()) {
			// Vanilla stops ticking the world when nobody is online; don't count these as fast ticks.
			b.pausedTicks++;
		} else {
			b.ticks++;
			b.tickNsSum += tickNs;
			b.tickNsMax = Math.max(b.tickNsMax, tickNs);
			b.tickNsMin = Math.min(b.tickNsMin, tickNs);
			b.msptHistogram[TickHistogram.bin(tickNs)]++;
			if (tickNs >= spikeThresholdNs && b.spikes.size() < 20) {
				b.spikes.add(spike(tickNs));
			}
		}

		if (System.currentTimeMillis() - b.startMillis >= 1000L) {
			bucket = new LiveBucket();
			try {
				Snapshotter.publish(server, b);
			} catch (RuntimeException e) {
				// A monitoring problem (e.g. an unusual mod) must never crash the server. Log each kind once.
				if (reportedErrors.add(e.getClass().getName() + e.getStackTrace().length + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : ""))) {
					eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.error(
							"Nice Control Center skipped a measurement because of an error; please report this", e);
				}
			}
		}
	}

	private static final java.util.Set<String> reportedErrors = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static boolean isPausedWhenEmpty() {
		if (server == null) {
			return false;
		}
		MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
		int seconds = accessor.nicecontrolcenter$pauseWhenEmptySeconds();
		return seconds > 0 && accessor.nicecontrolcenter$emptyTicks() >= seconds * 20;
	}

	private static Breakdown.Spike spike(long tickNs) {
		long attributed = 0;
		List<Breakdown.Share> phases = new ArrayList<>();
		for (Phase phase : Phase.VALUES) {
			long ns = tickPhaseNs[phase.ordinal()];
			attributed += ns;
			if (ns > 0) {
				phases.add(new Breakdown.Share(phase.label, ns / 1e6));
			}
		}
		long other = tickNs - attributed;
		if (other > 0) {
			phases.add(new Breakdown.Share(Phase.OTHER.label, other / 1e6));
		}
		phases.sort((a, b) -> Double.compare(b.ms, a.ms));
		Breakdown.Spike spike = new Breakdown.Spike();
		spike.time = System.currentTimeMillis();
		spike.ms = tickNs / 1e6;
		spike.phases = new ArrayList<>(phases.subList(0, Math.min(4, phases.size())));
		spike.top = topOfTick(5);
		return spike;
	}

	/** The functions, entity types and block entity types that took the most time this tick. */
	private static List<Breakdown.Share> topOfTick(int count) {
		Stat[] sorted = Arrays.copyOf(touched, touchedCount);
		Arrays.sort(sorted, (a, b) -> Long.compare(b.tickNs, a.tickNs));
		List<Breakdown.Share> top = new ArrayList<>();
		for (int i = 0; i < sorted.length && top.size() < count; i++) {
			Stat stat = sorted[i];
			if (stat.tickNs < 500_000L) {
				break;
			}
			String id = stat.kind == Stat.FUNCTION ? String.valueOf(stat.key) : Snapshotter.typeId(stat.key);
			String label = switch (stat.kind) {
				case Stat.FUNCTION -> id.equals("nicecontrolcenter:direct_commands") ? "Typed/command block commands" : "Function " + id;
				case Stat.BLOCK_ENTITY -> "Block entity " + id;
				default -> "Entity " + id;
			};
			top.add(new Breakdown.Share(label, stat.tickNs / 1e6));
		}
		return top;
	}
}
