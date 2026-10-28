package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.explorerseden.nicecontrolcenter.data.Breakdown;

/**
 * What the machine does to the server, read on its own thread (never the server thread): CPU time the host
 * takes away (steal on a VM, throttling by a container limit), I/O wait, pressure stall information, and how
 * much CPU each group of threads uses. Linux files are optional; elsewhere only the thread table is filled.
 */
public final class HostMetrics implements Runnable {
	private static final Path PROC_STAT = Path.of("/proc/stat");
	private static final Path PSI = Path.of("/proc/pressure");
	private static final Path CGROUP = Path.of("/sys/fs/cgroup");
	/** Thread CPU is read every this many seconds (it walks every thread). */
	private static final int THREAD_EVERY = 5;

	private static volatile HostMetrics instance;

	private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
	private volatile boolean running = true;
	private Thread thread;

	// Previous readings, for the change per second.
	private long[] lastStat;
	private long lastThrottledUs = -1;
	private long lastPeriods = -1;
	private long lastThrottledPeriods = -1;
	private final Map<Long, Long> lastThreadCpu = new HashMap<>();
	private int runs;

	/** Collected since the last {@link #drain}, guarded by this. */
	private Breakdown.Host pending = new Breakdown.Host();
	private final Map<String, Breakdown.ThreadRow> pendingThreads = new HashMap<>();

	private HostMetrics() {
	}

	public static void start() {
		if (instance != null) return;
		HostMetrics m = new HostMetrics();
		m.thread = new Thread(m, "Nice Control Center Host Metrics");
		m.thread.setDaemon(true);
		m.thread.start();
		instance = m;
	}

	public static void stop() {
		HostMetrics m = instance;
		instance = null;
		if (m != null) {
			m.running = false;
			m.thread.interrupt();
		}
	}

	/** Everything read since the last call, for one second's {@link Breakdown}. */
	public static void drainInto(Breakdown b) {
		HostMetrics m = instance;
		if (m == null) return;
		synchronized (m) {
			b.host = m.pending;
			m.pending = new Breakdown.Host();
			b.threads.putAll(m.pendingThreads);
			m.pendingThreads.clear();
		}
	}

	@Override
	public void run() {
		while (running) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				if (!running) return;
			}
			try {
				readOnce();
			} catch (RuntimeException e) {
				eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.debug("Performance: host metrics failed", e);
			}
		}
	}

	private void readOnce() {
		Breakdown.Host h = new Breakdown.Host();
		readStat(h);
		readCgroup(h);
		double cpu = psi("cpu"), io = psi("io"), memory = psi("memory");
		if (cpu >= 0 || io >= 0 || memory >= 0) {
			h.psiSeconds = 1;
			h.psiCpu = Math.max(0, cpu);
			h.psiIo = Math.max(0, io);
			h.psiMemory = Math.max(0, memory);
		}
		Map<String, Breakdown.ThreadRow> rows = ++runs % THREAD_EVERY == 0 ? readThreads() : Map.of();
		synchronized (this) {
			pending.add(h);
			rows.forEach((k, v) -> pendingThreads.computeIfAbsent(k, x -> new Breakdown.ThreadRow()).add(v));
		}
	}

	/** Steal and I/O wait in percent of all CPU time since the last reading. */
	private void readStat(Breakdown.Host h) {
		String first = firstLine(PROC_STAT);
		if (first == null || !first.startsWith("cpu ")) return;
		String[] parts = first.trim().split("\\s+");
		long[] v = new long[parts.length - 1];
		for (int i = 1; i < parts.length; i++) v[i - 1] = parseLong(parts[i]);
		// user nice system idle iowait irq softirq steal …
		if (lastStat != null && v.length >= 8 && lastStat.length == v.length) {
			long total = 0;
			for (int i = 0; i < Math.min(8, v.length); i++) total += v[i] - lastStat[i];
			if (total > 0) {
				h.procSeconds = 1;
				h.stealPercent = (v[7] - lastStat[7]) * 100.0 / total;
				h.iowaitPercent = (v[4] - lastStat[4]) * 100.0 / total;
			}
		}
		lastStat = v;
	}

	/** The container's CPU limit (cgroup v2, else v1) and how long it held the server back. */
	private void readCgroup(Breakdown.Host h) {
		long throttledUs = -1;
		long periods = -1;
		long throttledPeriods = -1;
		String max = firstLine(CGROUP.resolve("cpu.max"));
		if (max != null) {
			String[] p = max.trim().split("\\s+");
			if (p.length == 2 && !p[0].equals("max")) h.cpuLimitCores = parseLong(p[0]) / (double) Math.max(1, parseLong(p[1]));
			Map<String, Long> stat = keyValues(CGROUP.resolve("cpu.stat"));
			throttledUs = stat.getOrDefault("throttled_usec", -1L);
			periods = stat.getOrDefault("nr_periods", -1L);
			throttledPeriods = stat.getOrDefault("nr_throttled", -1L);
		} else {
			Path v1 = CGROUP.resolve("cpu");
			long quota = parseLong(String.valueOf(firstLine(v1.resolve("cpu.cfs_quota_us"))).trim());
			long period = parseLong(String.valueOf(firstLine(v1.resolve("cpu.cfs_period_us"))).trim());
			if (quota > 0 && period > 0) h.cpuLimitCores = quota / (double) period;
			Map<String, Long> stat = keyValues(v1.resolve("cpu.stat"));
			long ns = stat.getOrDefault("throttled_time", -1L);
			throttledUs = ns < 0 ? -1 : ns / 1000;
			periods = stat.getOrDefault("nr_periods", -1L);
			throttledPeriods = stat.getOrDefault("nr_throttled", -1L);
		}
		if (throttledUs >= 0 && lastThrottledUs >= 0) {
			h.cgroupSeconds = 1;
			h.throttledUs = Math.max(0, throttledUs - lastThrottledUs);
			h.periods = Math.max(0, periods - lastPeriods);
			h.throttledPeriods = Math.max(0, throttledPeriods - lastThrottledPeriods);
		}
		lastThrottledUs = throttledUs;
		lastPeriods = periods;
		lastThrottledPeriods = throttledPeriods;
	}

	/** "some avg10" of a pressure file (share of the last 10 s some task waited for that resource), or -1. */
	private static double psi(String resource) {
		String line = firstLine(PSI.resolve(resource));
		if (line == null || !line.startsWith("some")) return -1;
		for (String part : line.split("\\s+")) {
			if (part.startsWith("avg10=")) {
				try {
					return Double.parseDouble(part.substring(6));
				} catch (NumberFormatException e) {
					return -1;
				}
			}
		}
		return -1;
	}

	/** CPU time per group of threads since the last reading; numbered threads ("Worker-3") are grouped. */
	private Map<String, Breakdown.ThreadRow> readThreads() {
		Map<String, Breakdown.ThreadRow> out = new HashMap<>();
		if (!threads.isThreadCpuTimeSupported()) return out;
		long[] ids = threads.getAllThreadIds();
		ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
		Map<Long, Long> seen = new HashMap<>();
		for (int i = 0; i < ids.length; i++) {
			ThreadInfo info = infos[i];
			if (info == null) continue;
			long cpu = threads.getThreadCpuTime(ids[i]);
			if (cpu < 0) continue;
			seen.put(ids[i], cpu);
			Long before = lastThreadCpu.get(ids[i]);
			long used = before == null ? 0 : Math.max(0, cpu - before);
			Breakdown.ThreadRow row = out.computeIfAbsent(group(info.getThreadName()), k -> new Breakdown.ThreadRow());
			row.cpuNs += used;
			row.count++;
		}
		lastThreadCpu.clear();
		lastThreadCpu.putAll(seen);
		out.values().forEach(r -> r.countMax = r.count);
		return out;
	}

	/** "C2ME worker #3" → "C2ME worker #N", "Worker-Main-12" → "Worker-Main-N". */
	static String group(String name) {
		if (name == null) return "?";
		if (name.equals("Server thread")) return name;
		return name.replaceAll("\\d+", "N");
	}

	private static String firstLine(Path file) {
		try {
			if (!Files.isReadable(file)) return null;
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			return lines.isEmpty() ? null : lines.get(0);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static Map<String, Long> keyValues(Path file) {
		Map<String, Long> out = new HashMap<>();
		try {
			if (!Files.isReadable(file)) return out;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] p = line.trim().split("\\s+");
				if (p.length == 2) out.put(p[0], parseLong(p[1]));
			}
		} catch (IOException | RuntimeException e) {
			// Missing or unreadable: no values.
		}
		return out;
	}

	private static long parseLong(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
