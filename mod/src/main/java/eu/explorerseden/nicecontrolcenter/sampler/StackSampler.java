package eu.explorerseden.nicecontrolcenter.sampler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.core.Tracker;
import eu.explorerseden.nicecontrolcenter.data.Breakdown.ModRow;

/**
 * Looks at the server thread's stack at a fixed interval while it is ticking and counts which mod
 * was running. Catches mods whose cost is not tied to entities or blocks they add, e.g. mixins into
 * vanilla code or tick event handlers.
 *
 * <p>For every sample:
 * <ul>
 * <li>the owner of the innermost frame gets a "self" sample (its own code was running), and</li>
 * <li>if that is vanilla code, the innermost mod frame further down gets a "caused" sample
 * (vanilla work the mod asked for, e.g. a mod scanning entities every tick).</li>
 * </ul>
 */
public final class StackSampler implements Runnable {
	private static final int MAX_METHODS = 40;
	/** Call paths kept per between-ticks task. */
	public static final int MAX_PATHS = 30;

	private final ModResolver resolver = new ModResolver();
	private final int intervalMs;
	private volatile boolean running = true;
	private Thread thread;

	private Map<String, ModRow> rows = new HashMap<>();
	private long samples;
	/** Between ticks: samples by {@link #betweenKey} of what the server was doing. */
	private Map<String, Long> between = new HashMap<>();
	private long betweenSamples;
	/** Between ticks: per {@link #betweenKey} key, samples by the call path inside the task ({@link #path}). */
	private Map<String, Map<String, Long>> betweenPaths = new HashMap<>();
	/** Samples where the server thread was waiting instead of running, by {@link #waitKey}. */
	private Map<String, Long> waits = new HashMap<>();
	private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

	public StackSampler(int intervalMs) {
		this.intervalMs = Math.max(5, intervalMs);
	}

	public int intervalMs() {
		return intervalMs;
	}

	public void start() {
		thread = new Thread(this, "Nice Control Center Sampler");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY + 1);
		thread.start();
	}

	public void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}

	/** Hands over the samples collected since the last call. */
	public synchronized Drained drain() {
		Drained drained = new Drained(rows, samples, between, betweenSamples, betweenPaths, waits);
		rows = new HashMap<>();
		samples = 0;
		between = new HashMap<>();
		betweenSamples = 0;
		betweenPaths = new HashMap<>();
		waits = new HashMap<>();
		return drained;
	}

	public record Drained(Map<String, ModRow> rows, long samples, Map<String, Long> between, long betweenSamples,
			Map<String, Map<String, Long>> betweenPaths, Map<String, Long> waits) {
	}

	@Override
	public void run() {
		while (running) {
			try {
				Thread.sleep(intervalMs);
			} catch (InterruptedException e) {
				if (!running) {
					return;
				}
			}
			Thread target = Tracker.serverThread();
			if (target == null) {
				continue;
			}
			boolean tick = Tracker.inTick();
			if (!tick && !Tracker.betweenTicks()) {
				continue;
			}
			try {
				// Like getStackTrace, plus the thread's state and the lock it waits for (and who holds it).
				java.lang.management.ThreadInfo info = THREADS.getThreadInfo(target.getId(), Integer.MAX_VALUE);
				if (info == null) {
					continue;
				}
				StackTraceElement[] stack = info.getStackTrace();
				if (stack.length == 0) {
					continue;
				}
				String wait = waitOf(info, stack);
				// Only kept when the thread was still doing the same kind of thing after the stack was taken.
				if (tick && Tracker.inTick()) {
					record(stack);
					if (wait != null) {
						recordWait(TICK, wait, stack);
					}
				} else if (!tick && Tracker.betweenTicks()) {
					recordBetween(stack);
					if (wait != null) {
						recordWait(BETWEEN, wait, stack);
					}
				}
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.debug("Sampling failed", e);
			}
		}
	}

	private void record(StackTraceElement[] stack) {
		String selfOwner = null;
		String selfMethod = null;
		String causedOwner = null;
		String causedMethod = null;
		Set<String> onStack = new HashSet<>();

		for (StackTraceElement frame : stack) {
			String owner = resolver.owner(frame);
			if (owner.equals(ModResolver.JVM)) {
				continue;
			}
			boolean passThrough = ModResolver.isPassThrough(frame);
			if (selfOwner == null) {
				selfOwner = owner;
				selfMethod = describe(frame);
			} else if (causedOwner == null && selfOwner.equals(ModResolver.MINECRAFT) && !passThrough
					&& !owner.equals(ModResolver.MINECRAFT) && !owner.equals(ModResolver.SELF)) {
				causedOwner = owner;
				causedMethod = describe(frame);
			}
			if (!passThrough) {
				onStack.add(owner);
			}
		}
		if (selfOwner == null) {
			return;
		}

		synchronized (this) {
			samples++;
			ModRow self = row(selfOwner);
			self.self++;
			bump(self, selfMethod);
			if (causedOwner != null) {
				ModRow caused = row(causedOwner);
				caused.caused++;
				bump(caused, causedMethod + " → vanilla code");
			}
			for (String owner : onStack) {
				row(owner).total++;
			}
		}
	}

	/** Separates the parts of a between-ticks key: kind, task, mod id (may be empty). */
	public static final char SEP = '\u001f';
	public static final String PACKETS = "packets";
	public static final String CHUNKS = "chunks";
	public static final String MOD = "mod";
	public static final String OTHER = "other";

	private void recordBetween(StackTraceElement[] stack) {
		int[] anchor = new int[1];
		String key = betweenKey(stack, anchor);
		String path = path(stack, anchor[0]);
		synchronized (this) {
			betweenSamples++;
			if (between.size() < 200 || between.containsKey(key)) {
				between.merge(key, 1L, Long::sum);
				Map<String, Long> paths = betweenPaths.computeIfAbsent(key, k -> new HashMap<>());
				if (paths.size() < MAX_PATHS || paths.containsKey(path)) {
					paths.merge(path, 1L, Long::sum);
				}
			}
		}
	}

	/**
	 * Where inside a between-ticks task the sample landed: the two calls the task made first, then the innermost
	 * frame ("Entity.move › Entity.collide › … › PalettedContainer.get"). Tells why a packet handler got slow.
	 */
	private String path(StackTraceElement[] stack, int anchor) {
		java.util.List<String> parts = new java.util.ArrayList<>(3);
		int inner = -1;
		for (int i = 0; i < anchor; i++) {
			StackTraceElement frame = stack[i];
			if (!resolver.owner(frame).equals(ModResolver.JVM) && !ModResolver.isPassThrough(frame)) {
				inner = i;
				break;
			}
		}
		if (inner < 0) {
			return "(its own code)";
		}
		int last = anchor;
		for (int i = anchor - 1; i > inner && parts.size() < 2; i--) {
			StackTraceElement frame = stack[i];
			if (resolver.owner(frame).equals(ModResolver.JVM) || ModResolver.isPassThrough(frame)) {
				continue;
			}
			parts.add(describe(frame));
			last = i;
		}
		boolean gap = false;
		for (int i = last - 1; i > inner; i--) {
			if (!resolver.owner(stack[i]).equals(ModResolver.JVM) && !ModResolver.isPassThrough(stack[i])) {
				gap = true;
				break;
			}
		}
		if (gap) {
			parts.add("…");
		}
		parts.add(describe(stack[inner]));
		return String.join(" › ", parts);
	}

	/**
	 * What a stack taken between ticks was doing: a player packet being handled (by its handler method), chunk
	 * work (loading, generation results, ticket updates), or another queued task, named by the first method
	 * the server's task loop called; plus the innermost mod on the stack.
	 */
	private String betweenKey(StackTraceElement[] stack, int[] anchor) {
		String packet = null;
		int entryAt = -1;
		int packetAt = -1;
		String entry = null;
		boolean chunks = false;
		String mod = null;
		String modFrame = null;
		String self = null;
		// Outermost first: past the server's own loop, then into the task.
		for (int i = stack.length - 1; i >= 0; i--) {
			StackTraceElement frame = stack[i];
			String owner = resolver.owner(frame);
			if (owner.equals(ModResolver.JVM) || ModResolver.isPassThrough(frame)) {
				continue;
			}
			String className = frame.getClassName();
			if (entry == null) {
				if (isTaskLoop(className, frame.getMethodName())) {
					continue;
				}
				entry = describe(frame);
				entryAt = i;
			}
			if (packet == null && className.endsWith("PacketListenerImpl") && frame.getMethodName().startsWith("handle")) {
				packet = frame.getMethodName();
				packetAt = i;
			}
			if (!chunks && isChunkCode(className)) {
				chunks = true;
			}
			if (owner.equals(ModResolver.SELF)) {
				self = describe(frame);
			}
			if (!owner.equals(ModResolver.MINECRAFT)) {
				// Keeps going: the innermost mod wins.
				mod = owner;
				modFrame = describe(frame);
			}
		}
		if (entry == null) {
			entry = "the server's task loop";
		}
		// The path inside the task starts below the packet handler, or below the task's first method.
		anchor[0] = packetAt >= 0 ? packetAt : Math.max(entryAt, 0);
		String kind;
		String task;
		if (packet != null) {
			kind = PACKETS;
			task = packet;
		} else if (self != null) {
			// Nice Control Center's own work (its once-a-second measurements read chunks and entities too).
			kind = MOD;
			task = self;
			mod = ModResolver.SELF;
		} else if (chunks) {
			kind = CHUNKS;
			task = entry;
		} else if (mod != null) {
			kind = MOD;
			task = modFrame;
		} else {
			kind = OTHER;
			task = entry;
		}
		return kind + SEP + task + SEP + (mod == null ? "" : mod);
	}

	public static final String TICK = "tick";
	public static final String BETWEEN = "between";

	/**
	 * What the thread was waiting for instead of running, or null when it was running: a lock someone holds,
	 * a result from another thread (a future, a park), or the disk (file I/O shows as running in native code).
	 */
	static String waitOf(java.lang.management.ThreadInfo info, StackTraceElement[] stack) {
		Thread.State state = info.getThreadState();
		String owner = info.getLockOwnerName();
		String held = owner == null ? "" : " held by " + owner;
		switch (state) {
			case BLOCKED:
				return "lock " + shortLock(info.getLockName()) + held;
			case WAITING:
			case TIMED_WAITING: {
				String lock = shortLock(info.getLockName());
				if (lock.startsWith("CompletableFuture")) {
					return "a result from another thread (future)" + held;
				}
				return lock.isEmpty() ? "parked" + held : "waiting on " + lock + held;
			}
			case RUNNABLE:
				return isDiskIo(stack[0]) ? "disk I/O" : null;
			default:
				return null;
		}
	}

	private static boolean isDiskIo(StackTraceElement top) {
		String c = top.getClassName();
		return c.startsWith("sun.nio.ch.File") || c.startsWith("sun.nio.ch.UnixFileDispatcher") || c.startsWith("sun.nio.fs.")
				|| c.equals("java.io.RandomAccessFile") || c.equals("java.io.FileInputStream") || c.equals("java.io.FileOutputStream")
				|| c.equals("java.io.UnixFileSystem") || c.equals("sun.nio.ch.IOUtil") && top.getMethodName().startsWith("fdatasync");
	}

	/** "java.util.concurrent.locks.ReentrantLock$NonfairSync@5a2" → "ReentrantLock$NonfairSync". */
	private static String shortLock(String name) {
		if (name == null) {
			return "";
		}
		int at = name.indexOf('@');
		String c = at < 0 ? name : name.substring(0, at);
		return c.substring(c.lastIndexOf('.') + 1);
	}

	private void recordWait(String where, String wait, StackTraceElement[] stack) {
		// Who waits: the innermost frame that isn't the JDK itself, and the innermost mod on the stack.
		String frame = "";
		String mod = "";
		for (StackTraceElement f : stack) {
			String owner = resolver.owner(f);
			if (owner.equals(ModResolver.JVM) || ModResolver.isPassThrough(f)) {
				continue;
			}
			if (frame.isEmpty()) {
				frame = describe(f);
			}
			if (!owner.equals(ModResolver.MINECRAFT)) {
				mod = owner;
				break;
			}
		}
		String key = where + SEP + wait + SEP + frame + SEP + mod;
		synchronized (this) {
			if (waits.size() < 200 || waits.containsKey(key)) {
				waits.merge(key, 1L, Long::sum);
			}
		}
	}

	/** The server's own loop between ticks: running queued tasks and waiting for the next tick. */
	private static boolean isTaskLoop(String className, String method) {
		return switch (className) {
			case "net.minecraft.server.MinecraftServer", "net.minecraft.server.dedicated.DedicatedServer" ->
					method.equals("runServer") || method.equals("processPacketsAndTick") || method.equals("waitUntilNextTick") || method.equals("managedBlock") || method.equals("pollTask")
							|| method.equals("pollTaskInternal") || method.equals("doRunTask") || method.startsWith("lambda$spin")
							|| method.startsWith("lambda$waitUntilNextTick") || method.equals("runAllTasks");
			case "net.minecraft.util.thread.BlockableEventLoop", "net.minecraft.util.thread.ReentrantBlockableEventLoop" ->
					method.equals("pollTask") || method.equals("managedBlock") || method.equals("doRunTask") || method.equals("runAllTasks");
			case "net.minecraft.server.TickTask" -> method.equals("run");
			case "net.minecraft.server.level.ServerChunkCache" -> method.equals("pollTask");
			case "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor" -> true;
			default -> false;
		};
	}

	private static boolean isChunkCode(String className) {
		return className.startsWith("net.minecraft.server.level.Chunk") || className.startsWith("net.minecraft.server.level.ServerChunkCache")
				|| className.startsWith("net.minecraft.server.level.DistanceManager") || className.startsWith("net.minecraft.server.level.GenerationChunkHolder")
				|| className.startsWith("net.minecraft.server.level.ThreadedLevelLightEngine") || className.startsWith("net.minecraft.server.level.TicketType")
				|| className.startsWith("net.minecraft.world.level.chunk.") || className.startsWith("net.minecraft.world.level.lighting.");
	}

	private ModRow row(String owner) {
		return rows.computeIfAbsent(owner, k -> new ModRow());
	}

	private static void bump(ModRow row, String method) {
		if (row.methods.size() < MAX_METHODS || row.methods.containsKey(method)) {
			row.methods.merge(method, 1L, Long::sum);
		}
	}

	private static String describe(StackTraceElement frame) {
		String className = frame.getClassName();
		String simple = className.substring(className.lastIndexOf('.') + 1);
		String method = frame.getMethodName();
		if (ModResolver.isMixinHandler(frame)) {
			// handler$zza000$modid$onTick -> onTick, shown on the vanilla class it was mixed into
			String[] parts = method.split("\\$");
			String name = parts[parts.length - 1];
			for (int i = parts.length - 1; i >= 0 && name.chars().allMatch(Character::isDigit); i--) {
				name = parts[i];
			}
			return simple + "." + name + " (mixin)";
		}
		return simple + "." + method;
	}
}
