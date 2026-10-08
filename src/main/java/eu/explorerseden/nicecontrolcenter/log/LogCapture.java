package eu.explorerseden.nicecontrolcenter.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * Keeps the last server log lines in memory for the dashboard console, and passes warnings and
 * errors to the {@link ErrorWatcher}. Never logs itself, so it can't loop.
 */
public final class LogCapture extends AbstractAppender {
	private static final int CAPACITY = 2000;
	private static final int STACK_LINES = 12;

	/** One log line; {@code seq} increases by one per line. */
	public record Line(long seq, long time, String level, String logger, String thread, String message, String error) {
	}

	private static final Line[] ring = new Line[CAPACITY];
	private static long nextSeq = 1;
	private static LogCapture installed;

	private LogCapture() {
		super("NiceControlCenterCapture", null, null, true, Property.EMPTY_ARRAY);
	}

	/** Adds the appender to the root logger once. */
	public static synchronized void install() {
		if (installed != null) {
			return;
		}
		try {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			Configuration configuration = context.getConfiguration();
			LogCapture appender = new LogCapture();
			appender.start();
			configuration.addAppender(appender);
			configuration.getRootLogger().addAppender(appender, Level.ALL, null);
			context.updateLoggers();
			installed = appender;
		} catch (RuntimeException e) {
			// Another logging backend: the console and error watcher just stay empty.
		}
	}

	@Override
	public void append(LogEvent event) {
		try {
			String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
			Throwable thrown = event.getThrown();
			String error = thrown == null ? null : stack(thrown);
			Line line;
			synchronized (LogCapture.class) {
				line = new Line(nextSeq++, event.getTimeMillis(), event.getLevel().name(), event.getLoggerName(), event.getThreadName(),
						message, error);
				ring[(int) (line.seq() % CAPACITY)] = line;
			}
			if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
				ErrorWatcher.record(line, thrown);
			}
		} catch (RuntimeException e) {
			// Never let the monitor break logging.
		}
	}

	private static String stack(Throwable thrown) {
		StringWriter out = new StringWriter();
		thrown.printStackTrace(new PrintWriter(out));
		String[] lines = out.toString().split("\\R");
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < lines.length && i < STACK_LINES; i++) {
			text.append(lines[i]).append('\n');
		}
		if (lines.length > STACK_LINES) {
			text.append("\t… ").append(lines.length - STACK_LINES).append(" more lines");
		}
		return text.toString().stripTrailing();
	}

	/** Lines with a sequence number above {@code after}, oldest first. */
	public static synchronized List<Line> after(long after) {
		List<Line> result = new ArrayList<>();
		long from = Math.max(Math.max(after + 1, nextSeq - CAPACITY), 0);
		for (long seq = from; seq < nextSeq; seq++) {
			Line line = ring[(int) (seq % CAPACITY)];
			if (line != null && line.seq() == seq) {
				result.add(line);
			}
		}
		return result;
	}

	public static synchronized long lastSeq() {
		return nextSeq - 1;
	}
}
