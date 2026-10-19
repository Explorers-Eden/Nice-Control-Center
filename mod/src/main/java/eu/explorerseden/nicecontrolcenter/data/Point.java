package eu.explorerseden.nicecontrolcenter.data;

/** One second of the core metrics, used for the charts. */
public record Point(
		long t,
		double tps,
		double mspt,
		double msptMax,
		long heapUsed,
		long heapMax,
		long heapLive,
		double cpuProcess,
		double cpuSystem,
		int entities,
		int blockEntities,
		int chunks,
		int players,
		long gcMs,
		boolean paused,
		/** View and simulation distance in chunks (lowered while lagging when dynamic distances are on). */
		int view,
		int sim) {
}
