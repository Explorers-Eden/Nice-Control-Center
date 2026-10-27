package eu.explorerseden.nicecontrolcenter.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;

/**
 * What the mod logs while it does something the panel (or the dashboard) asked for starts with "[panel] ", the
 * mark the panel's console shows as a Panel entry, like its own notes. That's everything on the web thread, and
 * what a request runs on the server thread through {@link #during}.
 */
public final class PanelMark {
	private static final String WEB_THREAD = "Nice Control Center Web";
	private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
	private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");

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

	/** The logger, with the mark put in front of the message while a panel request is handled. */
	public static Logger wrap(Logger logger) {
		return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
			if (args != null && args.length > 0 && args[0] instanceof String msg && LEVELS.contains(method.getName()) && active() && !msg.startsWith("[panel] ")) {
				args[0] = "[panel] " + msg;
			}
			try {
				return method.invoke(logger, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}
}
