package eu.explorerseden.nicecontrolcenter.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;

/** CPU, heap and garbage collection readings from the JVM. */
public final class SystemMetrics {
	/**
	 * {@code heapLive} is the memory still in use after the last garbage collection: the real amount
	 * of live data. {@code heapUsed} also counts garbage not collected yet, so it is normally close to
	 * the maximum (especially with -Xms = -Xmx) and says little on its own.
	 */
	/** gcTimeMs: pauses that stop the server; gcBackgroundMs: time concurrent collections ran next to it. */
	public record Sample(double cpuProcess, double cpuSystem, long heapUsed, long heapMax, long heapLive, long gcTimeMs, long gcCount, long gcBackgroundMs) {
	}

	private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();
	private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();
	private static final List<MemoryPoolMXBean> POOLS = ManagementFactory.getMemoryPoolMXBeans();
	private static long lastGcTime = -1;
	private static long lastGcCount;
	private static long lastGcBackground = -1;

	private SystemMetrics() {
	}

	/** Reads current values; GC numbers are the change since the previous call. */
	public static Sample sample() {
		double cpuProcess = 0;
		double cpuSystem = 0;
		if (OS instanceof com.sun.management.OperatingSystemMXBean sun) {
			cpuProcess = Math.max(0, sun.getProcessCpuLoad()) * 100;
			cpuSystem = Math.max(0, sun.getCpuLoad()) * 100;
		}
		Runtime runtime = Runtime.getRuntime();
		long heapUsed = runtime.totalMemory() - runtime.freeMemory();
		long heapMax = runtime.maxMemory();
		long heapLive = 0;
		for (MemoryPoolMXBean pool : POOLS) {
			if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()) {
				MemoryUsage afterGc = pool.getCollectionUsage();
				if (afterGc != null) {
					heapLive += afterGc.getUsed();
				}
			}
		}
		if (heapLive <= 0) {
			// No collection yet (or unsupported collector): the current use is the best we have.
			heapLive = heapUsed;
		}

		long gcTime = 0;
		long gcCount = 0;
		long gcBackground = 0;
		for (GarbageCollectorMXBean gc : GCS) {
			if (!pausesTheServer(gc)) {
				gcBackground += Math.max(0, gc.getCollectionTime());
				continue;
			}
			gcTime += Math.max(0, gc.getCollectionTime());
			gcCount += Math.max(0, gc.getCollectionCount());
		}
		long gcTimeDelta = lastGcTime < 0 ? 0 : gcTime - lastGcTime;
		long gcCountDelta = lastGcTime < 0 ? 0 : gcCount - lastGcCount;
		lastGcTime = gcTime;
		lastGcCount = gcCount;
		long gcBackgroundDelta = lastGcBackground < 0 ? 0 : gcBackground - lastGcBackground;
		lastGcBackground = gcBackground;
		return new Sample(cpuProcess, cpuSystem, heapUsed, heapMax, heapLive, gcTimeDelta, gcCountDelta, gcBackgroundDelta);
	}

	/**
	 * Only collections that stop the game count as pauses. "G1 Concurrent GC", "ZGC … Cycles" and
	 * "Shenandoah Cycles" report background work running next to the server; it's counted apart, since
	 * a collector that never stops (ZGC with a nearly full heap) makes the server wait for memory
	 * without any pause showing up.
	 */
	private static boolean pausesTheServer(GarbageCollectorMXBean gc) {
		String name = gc.getName();
		return !name.contains("Concurrent") && !name.contains("Cycles");
	}

	public static int cores() {
		return OS.getAvailableProcessors();
	}
}
