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

	/** Merged breakdown of the last 1, 5, 15 or 60 minutes, cached until the next second arrives. */
	public static synchronized Breakdown window(int minutesWanted) {
		long end = seconds.isEmpty() ? 0 : seconds.getLast().end;
		if (end != cacheEnd) {
			cache.clear();
			cacheEnd = end;
		}
		Breakdown cached = cache.get(minutesWanted);
		if (cached != null) {
			return cached;
		}
		Breakdown merged = new Breakdown();
		if (minutesWanted > SECONDS / 60) {
			// Whole minutes plus the minute in progress.
			List<Breakdown> parts = new ArrayList<>(minutes);
			int skip = Math.max(0, parts.size() - (minutesWanted - 1));
			for (Breakdown part : parts.subList(skip, parts.size())) {
				merged.merge(part);
			}
			merged.merge(currentMinute);
		} else {
			List<Breakdown> parts = new ArrayList<>();
			Iterator<Breakdown> it = seconds.descendingIterator();
			while (it.hasNext() && parts.size() < minutesWanted * 60) {
				parts.add(it.next());
			}
			for (int i = parts.size() - 1; i >= 0; i--) {
				merged.merge(parts.get(i));
			}
		}
		cache.put(minutesWanted, merged);
		return merged;
	}
}
