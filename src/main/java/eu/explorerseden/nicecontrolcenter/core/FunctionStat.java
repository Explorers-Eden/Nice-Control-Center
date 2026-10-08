package eu.explorerseden.nicecontrolcenter.core;

import java.util.IdentityHashMap;
import java.util.Map;

/** Time spent in one function, split by command line. */
public final class FunctionStat extends Stat {
	public FunctionStat(Object id) {
		super(FUNCTION, id);
	}

	static final int MAX_LINES = 64;
	static final String OTHER_LINES = "(other lines)";
	static final String NO_LINE = "(function overhead)";

	/** Keyed by the parsed command string, which is the same instance every time a function runs. */
	public final Map<String, LineStat> lines = new IdentityHashMap<>();
	/** Phase in which this function was entered from outside any function. */
	public Phase calledFrom;
	/** Times the function was called. */
	public long runs;

	public LineStat line(String text) {
		if (text == null) {
			text = NO_LINE;
		}
		LineStat stat = lines.get(text);
		if (stat == null) {
			if (lines.size() >= MAX_LINES) {
				text = OTHER_LINES;
				stat = lines.get(text);
			}
			if (stat == null) {
				stat = new LineStat();
				lines.put(text, stat);
			}
		}
		return stat;
	}

	public static final class LineStat {
		public long selfNs;
		/** Queue entries charged to this line; one per executor, so entries/runs shows how many entities "execute as" matched. */
		public long entries;
		/** Times the line itself was started (one per function run). */
		public long runs;
	}
}
