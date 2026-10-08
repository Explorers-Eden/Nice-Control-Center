package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled tasks: at set times on chosen weekdays, or every N minutes, run a list of steps (restart
 * with a countdown in chat, stop, start, backup, console command, chat message, wait). Kept in
 * /data/panel/schedule.json, so it works without the database.
 */
public final class Scheduler {
	public static final class Task {
		public String id;
		public String name = "";
		public boolean enabled = true;
		/** "daily" (times + days) or "interval" (everyMinutes). */
		public String when = "daily";
		public List<String> times = new ArrayList<>(List.of("04:00"));
		/** 1 = Monday … 7 = Sunday; empty = every day. */
		public List<Integer> days = new ArrayList<>();
		public int everyMinutes = 60;
		public List<Step> steps = new ArrayList<>();
	}

	public static final class Step {
		/** restart, stop, start, backup, command, say, wait */
		public String type;
		/** command / chat message / backup label */
		public String text = "";
		/** restart: countdown in minutes before it happens; wait: seconds. */
		public int minutes = 5;
		public int seconds = 30;
	}

	public record Run(long time, boolean ok, String message) {
	}

	static final Set<String> STEP_TYPES = Set.of("restart", "stop", "start", "backup", "command", "say", "wait");
	/** Countdown messages at these many seconds before a restart (only those within the countdown). */
	private static final int[] WARN_AT = { 3600, 1800, 900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1 };

	private final Path file;
	private final Supervisor server;
	private final Backups backups;
	private final Audit audit;
	private final ZoneId zone;
	private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "scheduler");
		t.setDaemon(true);
		return t;
	});
	private final ExecutorService runner = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "scheduled-task");
		t.setDaemon(true);
		return t;
	});
	private volatile List<Task> tasks;
	private final Map<String, Run> lastRuns = new ConcurrentHashMap<>();
	private final Map<String, String> runningNow = new ConcurrentHashMap<>();
	/** Minute (epoch minutes) each task last fired in, so a slow tick can't run it twice. */
	private final Map<String, Long> firedAt = new ConcurrentHashMap<>();

	public Scheduler(Path file, Supervisor server, Backups backups, Audit audit, ZoneId zone) {
		this.file = file;
		this.server = server;
		this.backups = backups;
		this.audit = audit;
		this.zone = zone;
		List<Task> loaded = null;
		try {
			if (Files.exists(file)) loaded = Json.GSON.fromJson(Files.readString(file), new TypeToken<List<Task>>() { }.getType());
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read " + file + ": " + e.getMessage());
		}
		tasks = loaded != null ? loaded : new ArrayList<>();
	}

	public void start() {
		// Check every 15 seconds; a task fires once in the minute it's due.
		ticker.scheduleAtFixedRate(this::tick, 5, 15, TimeUnit.SECONDS);
	}

	// ── Editing ────────────────────────────────────────────────────────────

	public List<Map<String, Object>> view() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Task t : tasks) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("task", t);
			m.put("next", next(t, ZonedDateTime.now(zone)));
			m.put("last", lastRuns.get(t.id));
			m.put("running", runningNow.get(t.id));
			out.add(m);
		}
		return out;
	}

	public String zone() {
		return zone.getId();
	}

	/** Creates or replaces the task (by id). Returns an error, or null. */
	public synchronized String save(Task task) throws IOException {
		String problem = validate(task);
		if (problem != null) return problem;
		List<Task> next = new ArrayList<>(tasks);
		if (task.id == null || task.id.isBlank()) {
			task.id = UUID.randomUUID().toString().substring(0, 8);
			next.add(task);
		} else {
			boolean found = false;
			for (int i = 0; i < next.size(); i++) {
				if (next.get(i).id.equals(task.id)) {
					next.set(i, task);
					found = true;
				}
			}
			if (!found) return "No such task.";
		}
		write(next);
		return null;
	}

	public synchronized boolean delete(String id) throws IOException {
		List<Task> next = new ArrayList<>(tasks);
		boolean removed = next.removeIf(t -> t.id.equals(id));
		if (removed) write(next);
		return removed;
	}

	public Task task(String id) {
		return tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
	}

	private void write(List<Task> next) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.toJson(next), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		tasks = next;
	}

	private static String validate(Task t) {
		if (t.name == null || t.name.isBlank() || t.name.length() > 60) return "Give the task a name (up to 60 characters).";
		t.name = t.name.strip();
		if ("interval".equals(t.when)) {
			if (t.everyMinutes < 1 || t.everyMinutes > 10080) return "Every … minutes must be between 1 and 10080 (a week).";
		} else if ("daily".equals(t.when)) {
			if (t.times == null || t.times.isEmpty()) return "Add at least one time.";
			for (String time : t.times) {
				try {
					LocalTime.parse(time);
				} catch (DateTimeParseException e) {
					return "Times look like 04:00 (" + time + ").";
				}
			}
			if (t.days == null) t.days = new ArrayList<>();
			if (t.days.stream().anyMatch(d -> d < 1 || d > 7)) return "Unknown weekday.";
		} else {
			return "Unknown schedule type.";
		}
		if (t.steps == null || t.steps.isEmpty()) return "Add at least one step.";
		for (Step s : t.steps) {
			if (s.type == null || !STEP_TYPES.contains(s.type)) return "Unknown step.";
			if (s.text == null) s.text = "";
			if (s.text.contains("\n") || s.text.length() > 500) return "Commands and messages are one line, up to 500 characters.";
			if ((s.type.equals("command") || s.type.equals("say")) && s.text.isBlank()) return "A " + s.type + " step needs text.";
			if (s.type.equals("restart") && (s.minutes < 0 || s.minutes > 60)) return "The restart countdown is 0–60 minutes.";
			if (s.type.equals("wait") && (s.seconds < 1 || s.seconds > 3600)) return "Waits are 1–3600 seconds.";
		}
		return null;
	}

	// ── Timing ─────────────────────────────────────────────────────────────

	/** Next time the task is due, in epoch millis (0 when disabled). */
	long next(Task t, ZonedDateTime now) {
		if (!t.enabled) return 0;
		if ("interval".equals(t.when)) {
			long minute = now.toEpochSecond() / 60;
			long nextMinute = (minute / t.everyMinutes + 1) * t.everyMinutes;
			return nextMinute * 60_000;
		}
		for (int d = 0; d <= 7; d++) {
			LocalDate date = now.toLocalDate().plusDays(d);
			if (!t.days.isEmpty() && !t.days.contains(date.getDayOfWeek().getValue())) continue;
			long best = Long.MAX_VALUE;
			for (String time : t.times) {
				ZonedDateTime at = LocalDateTime.of(date, LocalTime.parse(time)).atZone(zone);
				if (at.isAfter(now) && at.toInstant().toEpochMilli() < best) best = at.toInstant().toEpochMilli();
			}
			if (best != Long.MAX_VALUE) return best;
		}
		return 0;
	}

	private boolean due(Task t, ZonedDateTime now) {
		if (!t.enabled) return false;
		long minute = now.toEpochSecond() / 60;
		if ("interval".equals(t.when)) return minute % t.everyMinutes == 0;
		if (!t.days.isEmpty() && !t.days.contains(now.getDayOfWeek().getValue())) return false;
		String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
		return t.times.stream().anyMatch(time -> LocalTime.parse(time).toString().equals(hhmm));
	}

	private void tick() {
		try {
			ZonedDateTime now = ZonedDateTime.now(zone);
			long minute = now.toEpochSecond() / 60;
			for (Task t : tasks) {
				if (!due(t, now)) continue;
				Long last = firedAt.get(t.id);
				if (last != null && last == minute) continue;
				firedAt.put(t.id, minute);
				run(t, "scheduler");
			}
		} catch (RuntimeException e) {
			System.err.println("Scheduler tick failed: " + e);
		}
	}

	// ── Running ────────────────────────────────────────────────────────────

	/** Starts the task in the background. Returns an error if it's already running. */
	public String run(Task task, String by) {
		if (runningNow.putIfAbsent(task.id, "starting") != null) return "This task is still running.";
		audit.log(by, "schedule.run", task.name, null);
		runner.submit(() -> {
			String error = null;
			try {
				for (int i = 0; i < task.steps.size() && error == null; i++) {
					Step step = task.steps.get(i);
					runningNow.put(task.id, describe(step));
					error = runStep(step, by);
				}
			} catch (InterruptedException e) {
				error = "Interrupted";
				Thread.currentThread().interrupt();
			} catch (RuntimeException e) {
				error = e.toString();
			} finally {
				runningNow.remove(task.id);
			}
			lastRuns.put(task.id, new Run(System.currentTimeMillis(), error == null, error == null ? "Done" : error));
			server.note("Task \"" + task.name + "\" " + (error == null ? "finished" : "stopped: " + error));
		});
		return null;
	}

	private String runStep(Step step, String by) throws InterruptedException {
		switch (step.type) {
			case "say" -> {
				if (!server.running()) return null;
				server.quietCommand("tellraw @a " + chat(step.text, "gold"));
			}
			case "command" -> {
				if (!server.running()) return "The server isn't running, so \"" + step.text + "\" couldn't run.";
				String cmd = step.text.startsWith("/") ? step.text.substring(1) : step.text;
				server.quietCommand(cmd);
				server.note("Task ran: " + cmd);
			}
			case "wait" -> Thread.sleep(step.seconds * 1000L);
			case "backup" -> {
				String error = backups.create(step.text.isBlank() ? "scheduled" : step.text, by);
				if (error != null) return error;
			}
			case "stop" -> {
				if (!server.running()) return null;
				String error = server.stop();
				if (error != null) return error;
				if (!server.awaitState(10 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED)) return "The server didn't stop within 10 minutes.";
			}
			case "start" -> {
				if (server.running()) return null;
				String error = server.start();
				if (error != null) return error;
				if (!server.awaitState(10 * 60_000L, Supervisor.State.RUNNING, Supervisor.State.CRASHED, Supervisor.State.STOPPED)) return "The server didn't finish starting within 10 minutes.";
				if (server.state() != Supervisor.State.RUNNING) return "The server didn't start (" + server.state().name().toLowerCase() + ").";
			}
			case "restart" -> {
				if (server.running()) {
					countdown(step);
					String error = server.stop();
					if (error != null) return error;
					if (!server.awaitState(10 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED)) return "The server didn't stop within 10 minutes.";
				}
				String error = server.start();
				if (error != null) return error;
				if (!server.awaitState(10 * 60_000L, Supervisor.State.RUNNING, Supervisor.State.CRASHED, Supervisor.State.STOPPED)) return "The server didn't finish starting within 10 minutes.";
				if (server.state() != Supervisor.State.RUNNING) return "The server didn't start again (" + server.state().name().toLowerCase() + ").";
			}
			default -> {
				return "Unknown step " + step.type;
			}
		}
		return null;
	}

	/** Chat warnings before a restart: "Server restarts in 5 minutes." … "in 1 second". */
	private void countdown(Step step) throws InterruptedException {
		int total = step.minutes * 60;
		if (total <= 0) return;
		String template = step.text.isBlank() ? "The server restarts in {time}." : step.text;
		long end = System.currentTimeMillis() + total * 1000L;
		for (int at : WARN_AT) {
			if (at > total) continue;
			long wait = end - at * 1000L - System.currentTimeMillis();
			if (wait > 0) Thread.sleep(wait);
			if (!server.running()) return;
			server.quietCommand("tellraw @a " + chat(template.replace("{time}", duration(at)), "gold"));
		}
		long rest = end - System.currentTimeMillis();
		if (rest > 0) Thread.sleep(rest);
		server.quietCommand("tellraw @a " + chat("Restarting now…", "gold"));
	}

	static String duration(int seconds) {
		if (seconds >= 60 && seconds % 60 == 0) {
			int m = seconds / 60;
			return m + (m == 1 ? " minute" : " minutes");
		}
		return seconds + (seconds == 1 ? " second" : " seconds");
	}

	private static String chat(String text, String color) {
		JsonObject json = new JsonObject();
		json.addProperty("text", text);
		json.addProperty("color", color);
		return json.toString();
	}

	private static String describe(Step s) {
		return switch (s.type) {
			case "restart" -> s.minutes > 0 ? "restart countdown (" + s.minutes + " min)" : "restarting";
			case "backup" -> "making a backup";
			case "wait" -> "waiting " + s.seconds + " s";
			case "stop" -> "stopping the server";
			case "start" -> "starting the server";
			default -> s.type;
		};
	}

	/** Weekday labels for the UI. */
	static List<String> dayNames() {
		List<String> out = new ArrayList<>();
		for (DayOfWeek d : DayOfWeek.values()) out.add(d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH));
		return out;
	}
}
