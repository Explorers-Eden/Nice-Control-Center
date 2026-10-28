package eu.explorerseden.nicecontrolcenter.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;

/**
 * Everything the mod logs starts with "[panel] ", the mark the panel's console shows as a Panel entry (coloured by
 * its section), and then its section: "[panel] Pets: rule First Join gave …". A line without a section gets
 * "Nice Control Center: ". {@link #active} still tells whether a panel request is being handled.
 */
public final class PanelMark {
	private static final String WEB_THREAD = "Nice Control Center Web";
	private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
	private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");
	/** "Claims: ", "Nice Actions: ", "Lag: ": a capitalised name of a few words, then a colon. */
	private static final java.util.regex.Pattern SECTION = java.util.regex.Pattern.compile("[A-Z][A-Za-z-]*( [A-Za-z-]+){0,3}: ");

	private PanelMark() {
	}

	public static boolean active() {
		return ACTIVE.get() || WEB_THREAD.equals(Thread.currentThread().getName());
	}

	/** Runs a task for a panel request (on the server thread): its log lines get the mark. */
	public static <T> T during(Supplier<T> task) {
		boolean was = ACTIVE.get();
		ACTIVE.set(true);
		try {
			return task.get();
		} finally {
			ACTIVE.set(was);
		}
	}

	/** "[panel] Section: text": the mark, and a section when the text has none. */
	static String mark(String msg) {
		String text = msg.startsWith("[panel] ") ? msg.substring("[panel] ".length()) : msg;
		if (text.startsWith("Nice Control Center · ")) text = "Nice Control Center: " + text.substring("Nice Control Center · ".length());
		if (!SECTION.matcher(text).lookingAt()) text = "Nice Control Center: " + text;
		return "[panel] " + text;
	}

	/** The logger, with the mark put in front of the message while a panel request is handled. */
	public static Logger wrap(Logger logger) {
		return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
			if (args != null && args.length > 0 && args[0] instanceof String msg && LEVELS.contains(method.getName())) {
				args[0] = mark(msg);
			}
			try {
				return method.invoke(logger, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}
}
