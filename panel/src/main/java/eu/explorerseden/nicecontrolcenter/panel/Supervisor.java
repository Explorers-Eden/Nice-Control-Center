package eu.explorerseden.nicecontrolcenter.panel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the Minecraft server as a child process: start, graceful stop, console in and out, and crash
 * recovery with backoff. One server per panel.
 */
public final class Supervisor {
	public enum State { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

	/** Waits before restarting after the 1st, 2nd, 3rd and later crash in a row. */
	private static final long[] BACKOFF_SECONDS = { 10, 30, 120, 600 };
	private static final int CRASH_LIMIT = 5;
	private static final long CRASH_WINDOW_MS = 15 * 60_000;
	private static final int KEEP_LINES = 2000;
	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
	/** Vanilla's "Done (4.321s)! For help, type "help"" marks the end of startup. */
	private static final Pattern DONE = Pattern.compile("Done \\([0-9.,]+s\\)!");

	private final Path serverDir;
	private final Supplier<PanelSettings> settings;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "supervisor-timer");
		t.setDaemon(true);
		return t;
	});
	private final Deque<String> lines = new ArrayDeque<>();
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private final List<Long> crashes = new ArrayList<>();

	private State state = State.STOPPED;
	private Process process;
	private BufferedWriter stdin;
	private boolean stopRequested;
	private boolean restartAfterStop;
	private long startedAt;
	private long runningSince;
	private Integer lastExitCode;
	private String message = "";
	private long nextRestartAt;
	private ScheduledFuture<?> pending;

	public Supervisor(Path serverDir, Supplier<PanelSettings> settings) {
		this.serverDir = serverDir;
		this.settings = settings;
	}

	// ── Control ────────────────────────────────────────────────────────────

	/** Returns an error message, or null when the server is starting. */
	public synchronized String start() {
		if (state == State.STARTING || state == State.RUNNING || state == State.STOPPING) return "The server is already " + state.name().toLowerCase() + ".";
		cancelPending();
		PanelSettings s = settings.get();
		String problem = checkReady(s);
		if (problem != null) {
			message = problem;
			return problem;
		}
		List<String> cmd = JvmFlags.command(s);
		try {
			process = new ProcessBuilder(cmd).directory(serverDir.toFile()).redirectErrorStream(true).start();
		} catch (IOException e) {
			message = "Could not start java: " + e.getMessage();
			return message;
		}
		stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		state = State.STARTING;
		stopRequested = false;
		restartAfterStop = false;
		startedAt = System.currentTimeMillis();
		runningSince = 0;
		lastExitCode = null;
		message = "";
		panelLine("Starting: " + String.join(" ", cmd));
		Process p = process;
		Thread reader = new Thread(() -> pump(p), "server-output");
		reader.setDaemon(true);
		reader.start();
		return null;
	}

	/** Sends "stop" and escalates if the server doesn't exit in time. */
	public synchronized String stop() {
		cancelPending();
		if (state == State.CRASHED) {
			state = State.STOPPED;
			message = "";
			return null;
		}
		if (state != State.STARTING && state != State.RUNNING) return state == State.STOPPING ? null : "The server isn't running.";
		stopRequested = true;
		state = State.STOPPING;
		panelLine("Stopping the server");
		send("stop");
		Process p = process;
		long timeout = settings.get().stopTimeoutSeconds;
		timer.schedule(() -> {
			if (p.isAlive()) {
				panelLine("Still running after " + timeout + " s, sending SIGTERM");
				p.destroy();
				timer.schedule(() -> {
					if (p.isAlive()) {
						panelLine("Still running, killing the process");
						p.destroyForcibly();
					}
				}, 15, TimeUnit.SECONDS);
			}
		}, timeout, TimeUnit.SECONDS);
		return null;
	}

	public synchronized String restart() {
		if (state != State.STARTING && state != State.RUNNING) return start();
		String error = stop();
		if (error == null) restartAfterStop = true;
		return error;
	}

	/** Last resort: kills the process without saving. */
	public synchronized String kill() {
		cancelPending();
		if (process == null || !process.isAlive()) return "The server isn't running.";
		stopRequested = true;
		state = State.STOPPING;
		panelLine("Killing the server process");
		process.destroyForcibly();
		return null;
	}

	/** Writes a console command to the server's stdin. */
	public synchronized String command(String command) {
		String line = command.replaceAll("[\\r\\n]+", " ").strip();
		if (line.startsWith("/")) line = line.substring(1);
		if (line.isEmpty()) return "Empty command.";
		if (state != State.STARTING && state != State.RUNNING) return "The server isn't running.";
		addLine("> " + line);
		return send(line) ? null : "Could not reach the server's console.";
	}

	/** Container shutdown: stop gracefully and wait for the process to exit. */
	public void shutdown() {
		Process p;
		synchronized (this) {
			cancelPending();
			p = process;
			if (p == null || !p.isAlive()) return;
			if (state != State.STOPPING) stop();
		}
		try {
			p.waitFor(settings.get().stopTimeoutSeconds + 20L, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ── Status and console ─────────────────────────────────────────────────

	public synchronized Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("state", state.name());
		out.put("message", message);
		out.put("pid", process != null && process.isAlive() ? process.pid() : null);
		out.put("startedAt", state == State.STOPPED || state == State.CRASHED ? 0 : startedAt);
		out.put("runningSince", runningSince);
		out.put("lastExitCode", lastExitCode);
		out.put("nextRestartAt", pending != null ? nextRestartAt : 0);
		pruneCrashes();
		out.put("recentCrashes", crashes.size());
		out.put("ready", checkReady(settings.get()));
		return out;
	}

	public synchronized List<String> history() {
		return new ArrayList<>(lines);
	}

	public void listen(Consumer<String> listener) {
		listeners.add(listener);
	}

	public void unlisten(Consumer<String> listener) {
		listeners.remove(listener);
	}

	// ── Internals ──────────────────────────────────────────────────────────

	private String checkReady(PanelSettings s) {
		if (!Files.isRegularFile(serverDir.resolve(s.serverJar))) {
			// Fabric's launcher from fabricmc.net is called fabric-server-mc.<version>-loader.<version>-launcher.<version>.jar.
			try (Stream<Path> files = Files.list(serverDir)) {
				String found = files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("fabric-server") && n.endsWith(".jar")).sorted().findFirst().orElse(null);
				if (found != null) return "No " + s.serverJar + " in the server folder, but there is " + found + ". Set it as the server jar under Startup & Java.";
			} catch (IOException e) {
				// Fall through to the plain message.
			}
			return "No " + s.serverJar + " in the server folder yet.";
		}
		if (!eulaAccepted()) return "The Minecraft EULA hasn't been accepted yet.";
		return null;
	}

	public boolean eulaAccepted() {
		try {
			return Files.readAllLines(serverDir.resolve("eula.txt")).stream().anyMatch(l -> l.strip().equalsIgnoreCase("eula=true"));
		} catch (IOException e) {
			return false;
		}
	}

	public void acceptEula() throws IOException {
		Files.writeString(serverDir.resolve("eula.txt"),
				"# Accepted in the Nice Control Center panel (https://aka.ms/MinecraftEULA)\neula=true\n", StandardCharsets.UTF_8);
	}

	private void pump(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				line = ANSI.matcher(line).replaceAll("");
				addLine(line);
				if (DONE.matcher(line).find()) {
					synchronized (this) {
						if (process == p && state == State.STARTING) {
							state = State.RUNNING;
							runningSince = System.currentTimeMillis();
						}
					}
				}
			}
		} catch (IOException e) {
			// The stream closes when the process exits.
		}
		int code;
		try {
			code = p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		exited(p, code);
	}

	private synchronized void exited(Process p, int code) {
		if (process != p) return;
		process = null;
		stdin = null;
		lastExitCode = code;
		if (stopRequested) {
			state = State.STOPPED;
			panelLine("Server stopped (exit code " + code + ")");
			if (restartAfterStop) {
				restartAfterStop = false;
				String error = start();
				if (error != null) panelLine("Restart failed: " + error);
			}
			return;
		}
		state = State.CRASHED;
		crashes.add(System.currentTimeMillis());
		pruneCrashes();
		String report = newestCrashReport();
		message = "The server exited unexpectedly (exit code " + code + ")" + (report != null ? ", crash report " + report : "") + ".";
		panelLine(message);
		if (!settings.get().autoRestart) return;
		if (crashes.size() >= CRASH_LIMIT) {
			message += " It crashed " + crashes.size() + " times in 15 minutes, so it won't be restarted automatically.";
			panelLine("Giving up on automatic restarts after " + crashes.size() + " crashes in 15 minutes");
			return;
		}
		long delay = BACKOFF_SECONDS[Math.min(crashes.size(), BACKOFF_SECONDS.length) - 1];
		nextRestartAt = System.currentTimeMillis() + delay * 1000;
		panelLine("Restarting in " + delay + " s");
		pending = timer.schedule(() -> {
			synchronized (this) {
				pending = null;
				String error = start();
				if (error != null) panelLine("Automatic restart failed: " + error);
			}
		}, delay, TimeUnit.SECONDS);
	}

	private String newestCrashReport() {
		Path dir = serverDir.resolve("crash-reports");
		if (!Files.isDirectory(dir)) return null;
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> {
				try {
					return Files.getLastModifiedTime(f).toMillis() >= startedAt;
				} catch (IOException e) {
					return false;
				}
			}).map(f -> f.getFileName().toString()).max(String::compareTo).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private void pruneCrashes() {
		long cutoff = System.currentTimeMillis() - CRASH_WINDOW_MS;
		crashes.removeIf(t -> t < cutoff);
	}

	private void cancelPending() {
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
	}

	private boolean send(String line) {
		if (stdin == null) return false;
		try {
			stdin.write(line);
			stdin.newLine();
			stdin.flush();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** The panel's own notes in the console, marked so the UI can tell them apart. */
	private void panelLine(String text) {
		addLine("[panel] " + text);
	}

	private void addLine(String line) {
		synchronized (this) {
			lines.addLast(line);
			while (lines.size() > KEEP_LINES) lines.removeFirst();
		}
		for (Consumer<String> listener : listeners) {
			try {
				listener.accept(line);
			} catch (RuntimeException e) {
				listeners.remove(listener);
			}
		}
	}
}
