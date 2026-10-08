package eu.explorerseden.nicecontrolcenter.core;

/** Tick times in 0.25 ms bins up to 250 ms; the last bin collects everything slower. */
public final class TickHistogram {
	public static final long BIN_NS = 250_000L;
	public static final int BINS = 1001;

	private TickHistogram() {
	}

	public static int bin(long tickNs) {
		return (int) Math.min(BINS - 1, tickNs / BIN_NS);
	}

	/** First bin that holds ticks of at least this many milliseconds. */
	public static int binForMs(double ms) {
		return (int) Math.min(BINS - 1, Math.ceil(ms * 1_000_000 / BIN_NS));
	}

	/** Tick time in ms at the given percentile (0..1), interpolated within its bin. */
	public static double percentile(int[] histogram, double p) {
		long total = 0;
		for (int count : histogram) {
			total += count;
		}
		if (total == 0) {
			return 0;
		}
		double target = Math.max(1, total * p);
		long seen = 0;
		for (int i = 0; i < histogram.length; i++) {
			if (histogram[i] > 0 && seen + histogram[i] >= target) {
				double within = (target - seen) / histogram[i];
				return (i + within) * BIN_NS / 1e6;
			}
			seen += histogram[i];
		}
		return histogram.length * BIN_NS / 1e6;
	}
}
