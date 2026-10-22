package eu.explorerseden.nicecontrolcenter.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Recent measurements. Written by the server thread, read by web and command code.
 *
 * <ul>
 * <li>Per-second breakdowns for the last 15 minutes (1/5/15 minute windows).</li>
 * <li>Per-minute breakdowns for the last hour (60 minute window), so an hour costs 60 entries
 * instead of 3600.</li>
 * <li>Per-second chart points for the last hour; these are tiny.</li>
 * </ul>
 */
public final class History {
	public static final int SECONDS = 15 * 60;
	public static final int MINUTES = 60;
	public static final int POINTS = 60 * 60;

	private static final ArrayDeque<Breakdown> seconds = new ArrayDeque<>();
	private static final ArrayDeque<Breakdown> minutes = new ArrayDeque<>();
	private static final ArrayDeque<Point> points = new ArrayDeque<>();
	private static Breakdown currentMinute = new Breakdown();
	private static final Map<Integer, Breakdown> cache = new HashMap<>();
	private static long cacheEnd;

	private History() {
	}

	public static synchronized void add(Breakdown breakdown, Point point) {
		seconds.addLast(breakdown);
		while (seconds.size() > SECONDS) {
			seconds.removeFirst();
		}
		points.addLast(point);
		while (points.size() > POINTS) {
			points.removeFirst();
		}
		currentMinute.merge(breakdown);
		if (currentMinute.seconds >= 60) {
			minutes.addLast(currentMinute);
			currentMinute = new Breakdown();
			while (minutes.size() > MINUTES) {
				minutes.removeFirst();
			}
		}
	}

	public static synchronized void clear() {
		seconds.clear();
		minutes.clear();
		points.clear();
		currentMinute = new Breakdown();
		cache.clear();
	}

	public static synchronized Point latestPoint() {
		return points.isEmpty() ? null : points.getLast();
	}

	public static synchronized List<Point> pointsAfter(long time) {
		List<Point> result = new ArrayList<>();
		Iterator<Point> it = points.descendingIterator();
		while (it.hasNext()) {
			Point point = it.next();
			if (point.t() <= time) {
				break;
			}
			result.add(point);
		}
		java.util.Collections.reverse(result);
		return result;
	}

	/** Merged breakdown of the last {@code count} seconds (lag alerts). */
	public static synchronized Breakdown lastSeconds(int count) {
		List<Breakdown> parts = new ArrayList<>();
		Iterator<Breakdown> it = seconds.descendingIterator();
		while (it.hasNext() && parts.size() < count) {
			parts.add(it.next());
		}
		Breakdown merged = new Breakdown();
		for (int i = parts.size() - 1; i >= 0; i--) {
			merged.merge(parts.get(i));
		}
		return merged;
	}

	/** Tick times and GC pauses of the last few seconds: what the server is doing right now. */
	public record Recent(int seconds, long ticks, double msptMin, double msptMedian, double msptP95, double msptMax, long wallMs,
			long gcTimeMs, long gcCount, long gcBackgroundMs, long tickNsSum, long betweenBusyNs) {
	}

	/** Cheaper than {@link #lastSeconds}: only the tick times and GC of the last {@code count} seconds. */
	public static synchronized Recent recent(int count) {
		int[] histogram = new int[eu.explorerseden.nicecontrolcenter.core.TickHistogram.BINS];
		int n = 0;
		long ticks = 0, minNs = Long.MAX_VALUE, maxNs = 0, wallMs = 0, gcTimeMs = 0, gcCount = 0, gcBackgroundMs = 0, tickNsSum = 0, betweenNs = 0;
		Iterator<Breakdown> it = seconds.descendingIterator();
		while (it.hasNext() && n < count) {
			Breakdown b = it.next();
			n++;
			wallMs += b.wallMs;
			gcTimeMs += b.gcTimeMs;
			gcCount += b.gcCount;
			gcBackgroundMs += b.gcBackgroundMs;
			betweenNs += b.betweenBusyNs;
			if (b.ticks == 0) {
				continue;
			}
			ticks += b.ticks;
			tickNsSum += b.tickNsSum;
			minNs = Math.min(minNs, b.tickNsMin);
			maxNs = Math.max(maxNs, b.tickNsMax);
			for (int i = 0; i < histogram.length; i++) {
				histogram[i] += b.msptHistogram[i];
			}
		}
		return new Recent(n, ticks, ticks == 0 ? 0 : minNs / 1e6,
				eu.explorerseden.nicecontrolcenter.core.TickHistogram.percentile(histogram, 0.5),
				eu.explorerseden.nicecontrolcenter.core.TickHistogram.percentile(histogram, 0.95), maxNs / 1e6, wallMs, gcTimeMs, gcCount, gcBackgroundMs, tickNsSum, betweenNs);
	}

	/** The last minutes as chart points plus per-minute breakdowns, to start a lag recording with. */
	public record PreRoll(List<Point> points, List<Breakdown> minutes) {
	}

	public static synchronized PreRoll preRoll(int minutesBack) {
		long since = System.currentTimeMillis() - minutesBack * 60_000L;
		List<Point> recent = new ArrayList<>();
		for (Point point : points) {
			if (point.t() >= since) {
				recent.add(point);
			}
		}
		List<Breakdown> merged = new ArrayList<>();
		Breakdown minute = new Breakdown();
		for (Breakdown second : seconds) {
			if (second.end < since) {
				continue;
			}
			minute.merge(second);
			if (minute.seconds >= 60) {
				merged.add(minute);
				minute = new Breakdown();
			}
		}
		if (minute.seconds > 0) {
			merged.add(minute);
		}
		return new PreRoll(recent, merged);
	}

	/**
	 * Merged breakdown of the last 1, 5, 15 or 60 minutes, cached until the next second arrives. Merging an hour
	 * takes a while, so it happens outside the lock: the server thread adds its second meanwhile instead of waiting.
	 */
	public static Breakdown window(int minutesWanted) {
		List<Breakdown> parts = new ArrayList<>();
		long end;
		synchronized (History.class) {
			end = seconds.isEmpty() ? 0 : seconds.getLast().end;
			if (end != cacheEnd) {
				cache.clear();
				cacheEnd = end;
			}
			Breakdown cached = cache.get(minutesWanted);
			if (cached != null) {
				return cached;
			}
			if (minutesWanted > SECONDS / 60) {
				// Whole minutes plus the minute in progress (copied: the server thread keeps adding to it).
				List<Breakdown> all = new ArrayList<>(minutes);
				parts.addAll(all.subList(Math.max(0, all.size() - (minutesWanted - 1)), all.size()));
				Breakdown current = new Breakdown();
				current.merge(currentMinute);
				parts.add(current);
			} else {
				Iterator<Breakdown> it = seconds.descendingIterator();
				while (it.hasNext() && parts.size() < minutesWanted * 60) {
					parts.add(it.next());
				}
				java.util.Collections.reverse(parts);
			}
		}
		// Seconds and finished minutes don't change once added, so they can be read without the lock.
		Breakdown merged = new Breakdown();
		for (Breakdown part : parts) {
			merged.merge(part);
		}
		synchronized (History.class) {
			if (cacheEnd == end) {
				cache.put(minutesWanted, merged);
			}
		}
		return merged;
	}
}
