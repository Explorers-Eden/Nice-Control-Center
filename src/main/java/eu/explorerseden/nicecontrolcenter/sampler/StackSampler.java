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

	private final ModResolver resolver = new ModResolver();
	private final int intervalMs;
	private volatile boolean running = true;
	private Thread thread;

	private Map<String, ModRow> rows = new HashMap<>();
	private long samples;

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
		Drained drained = new Drained(rows, samples);
		rows = new HashMap<>();
		samples = 0;
		return drained;
	}

	public record Drained(Map<String, ModRow> rows, long samples) {
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
			if (target == null || !Tracker.inTick()) {
				continue;
			}
			try {
				StackTraceElement[] stack = target.getStackTrace();
				if (stack.length > 0 && Tracker.inTick()) {
					record(stack);
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
