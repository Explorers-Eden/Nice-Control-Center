package eu.explorerseden.nicecontrolcenter.core;

import java.util.Arrays;

import net.minecraft.resources.Identifier;

/**
 * Which function (and which of its command lines) runs at each frame depth of one command
 * execution. Commands are queued rather than nested, so this is how a queue entry finds out what
 * function it belongs to.
 *
 * <p>Also caches the matching stats per depth: a busy tick function can run tens of thousands of
 * queue entries per tick, so each one should only cost an array lookup.
 */
public final class FunctionFrames {
	/** Implemented by ExecutionContext through a mixin. */
	public interface Holder {
		FunctionFrames nicecontrolcenter$frames();
	}

	private Identifier[] functions = new Identifier[16];
	private String[] lines = new String[16];
	private FunctionStat[] stats = new FunctionStat[16];
	private FunctionStat.LineStat[] lineStats = new FunctionStat.LineStat[16];
	private LiveBucket bucket;

	public Identifier function(int depth) {
		return depth >= 0 && depth < functions.length ? functions[depth] : null;
	}

	public String line(int depth) {
		return depth >= 0 && depth < lines.length ? lines[depth] : null;
	}

	public void call(int depth, Identifier function) {
		ensure(depth);
		functions[depth] = function;
		lines[depth] = null;
		stats[depth] = null;
		lineStats[depth] = null;
	}

	public void line(int depth, String text) {
		ensure(depth);
		lines[depth] = text;
		lineStats[depth] = null;
	}

	/** Stats of the function running at this depth, in the given second's bucket. */
	public FunctionStat stat(int depth, LiveBucket current) {
		if (depth < 0) {
			return current.function(null);
		}
		ensure(depth);
		if (current != bucket) {
			Arrays.fill(stats, null);
			Arrays.fill(lineStats, null);
			bucket = current;
		}
		FunctionStat stat = stats[depth];
		if (stat == null) {
			stat = current.function(functions[depth]);
			stats[depth] = stat;
		}
		return stat;
	}

	/** Stats of the command line running at this depth; call {@link #stat} first. */
	public FunctionStat.LineStat lineStat(int depth, FunctionStat stat) {
		if (depth < 0) {
			return stat.line(null);
		}
		FunctionStat.LineStat line = lineStats[depth];
		if (line == null) {
			line = stat.line(lines[depth]);
			lineStats[depth] = line;
		}
		return line;
	}

	private void ensure(int depth) {
		if (depth >= functions.length) {
			int size = Math.max(depth + 1, functions.length * 2);
			functions = Arrays.copyOf(functions, size);
			lines = Arrays.copyOf(lines, size);
			stats = Arrays.copyOf(stats, size);
			lineStats = Arrays.copyOf(lineStats, size);
		}
	}
}
