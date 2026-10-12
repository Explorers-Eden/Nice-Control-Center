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
 * with a countdown in chat, stop, start, backup, console command, chat message, wait, resetting the
 * outer wilds, pregenerating). Kept in
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
		/** restart, stop, start, backup, command, say, wait, trim, pregen */
		public String type;
		/** command / chat message / backup label / countdown message; pregen: "wilds" or "all" */
		public String text = "";
		/** restart and trim: countdown in minutes before it happens; wait: seconds. */
		public int minutes = 5;
		public int seconds = 30;
		/** pregen: "full", "slow" or "pause" while players are online. */
		public String mode = "slow";
		/** trim: make a backup before trimming. */
		public boolean backup = false;
	}

	public record Run(long time, boolean ok, String message) {
	}

	static final Set<String> STEP_TYPES = Set.of("restart", "stop", "start", "backup", "command", "say", "wait", "trim", "pregen", "cleanup", "maintenance");
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
	private volatile Trimmer trimmer;
	private volatile DashboardProxy mod;
	private volatile Notifier notifier = Notifier.NONE;

	void useNotifier(Notifier n) {
		notifier = n;
	}
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

	/** For the trim and pregen steps. */
	private ServerTuning tuning;

	void useTuning(ServerTuning tuning) {
		this.tuning = tuning;
	}

	void useWorld(Trimmer trimmer, DashboardProxy mod) {
		this.trimmer = trimmer;
		this.mod = mod;
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
			if ((s.type.equals("restart") || s.type.equals("trim")) && (s.minutes < 0 || s.minutes > 60)) return "The countdown is 0–60 minutes.";
			if (s.type.equals("pregen") && !s.text.equals("all")) s.text = "wilds";
			if (s.type.equals("maintenance") && !s.text.equals("off")) s.text = "on";
			if (s.type.equals("cleanup")) {
				if (!s.text.matches("items|excess|excess:\\d{1,3}")) s.text = "items";
				if (s.seconds < 0 || s.seconds > 600) return "The warning before a cleanup is 0–600 seconds.";
			}
			if (s.type.equals("wait") && (s.seconds < 1 || s.seconds > 3600)) return "Waits are 1–3600 seconds.";
		}
		return null;
	}

	// ── Timing ─────────────────────────────────────────────────────────────

	/** When an enabled task with the given step type runs next (epoch millis), or 0. */
	public long nextWith(String stepType) {
		ZonedDateTime now = ZonedDateTime.now(zone);
		long best = 0;
		for (Task t : tasks) {
			if (t.steps.stream().noneMatch(s -> stepType.equals(s.type))) continue;
			long at = next(t, now);
			if (at > 0 && (best == 0 || at < best)) best = at;
		}
		return best;
	}

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
			if (error != null) notifier.alert("taskFailed", Map.of("name", task.name, "message", error));
			server.note("Task \"" + task.name + "\" " + (error == null ? "finished" : "stopped: " + error));
		});
		return null;
	}

	private String runStep(Step step, String by) throws InterruptedException {
		switch (step.type) {
			case "say" -> {
				if (!server.running()) return null;
				server.quietCommand(say(step.text));
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
			case "trim" -> {
				return resetWilds(step, by);
			}
			case "pregen" -> {
				return pregen("all".equals(step.text), step.mode);
			}
			case "maintenance" -> {
				if (tuning == null) return "Maintenance mode isn't available.";
				try {
					return tuning.setMaintenance(!"off".equals(step.text), by);
				} catch (java.io.IOException e) {
					return "Couldn't switch maintenance mode: " + e.getMessage();
				}
			}
			case "cleanup" -> {
				return cleanup(step);
			}
			case "restart" -> {
				if (server.running()) {
					countdown(step, "The server restarts in {time}.", "Restarting now…");
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

	/**
	 * Stops the server (after a countdown), trims everything outside each dimension's soft border, and
	 * starts it again if it was running.
	 */
	private String resetWilds(Step step, String by) throws InterruptedException {
		Trimmer t = trimmer;
		if (t == null) return "The trimmer isn't available.";
		List<Trimmer.Area> areas = t.wildsAreas();
		if (areas.isEmpty()) return "No borders are set (World tab), so there are no outer wilds to reset.";
		boolean wasRunning = server.running();
		if (wasRunning) {
			countdown(step, "The outer wilds reset in {time}.", "Resetting the outer wilds now…");
			String error = server.stop();
			if (error != null) return error;
			if (!server.awaitState(10 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED)) return "The server didn't stop within 10 minutes.";
		}
		String problem = t.start(areas, by, step.backup);
		if (problem == null) {
			String result = t.awaitResult(6 * 3600_000L);
			if (result == null || !(result.startsWith("Done") || result.equals(Trimmer.NOTHING))) problem = result == null ? "The trim ended without a result." : result;
		} else if (problem.equals(Trimmer.NOTHING)) {
			problem = null;
		}
		if (wasRunning) {
			String error = server.start();
			if (error == null && !server.awaitState(10 * 60_000L, Supervisor.State.RUNNING, Supervisor.State.CRASHED, Supervisor.State.STOPPED)) error = "The server didn't finish starting within 10 minutes.";
			if (error == null && server.state() != Supervisor.State.RUNNING) error = "The server didn't start again (" + server.state().name().toLowerCase() + ").";
			if (problem == null) problem = error;
		}
		return problem;
	}

	/** Pregenerates every dimension with borders that has pregen on, one after another (in the mod). */
	/** Removes dropped items and XP (after a warning in chat), or mobs over a per-chunk cap. */
	private String cleanup(Step step) throws InterruptedException {
		DashboardProxy m = mod;
		if (m == null) return "Cleanup isn't available.";
		if (!server.running()) return null;
		boolean items = !step.text.startsWith("excess");
		if (items && step.seconds > 0) {
			server.quietCommand(say("&eDropped items and XP orbs are cleared in " + step.seconds + " seconds."));
			Thread.sleep(step.seconds * 1000L);
			if (!server.running()) return null;
		}
		// "excess" uses the mob limits from Performance → Tuning; "excess:24" (older schedules) one limit for every kind.
		String query = items ? "kind=items" : step.text.startsWith("excess:") ? "kind=excess&perChunk=" + step.text.substring("excess:".length()) : "kind=excess";
		String error = m.post("/api/cleanup/run?" + query, new JsonObject());
		if (error == null) server.note("Task: " + (items ? "cleared dropped items and XP" : "removed mobs over their per-chunk limit"));
		return error;
	}

	private String pregen(boolean all, String players) throws InterruptedException {
		Trimmer t = trimmer;
		DashboardProxy m = mod;
		if (t == null || m == null) return "Pregenerating isn't available.";
		if (!server.running()) return "The server isn't running, so it can't pregenerate.";
		int started = 0;
		for (Map.Entry<String, eu.explorerseden.nicecontrolcenter.Borders.Border> e : t.borders().entrySet()) {
			eu.explorerseden.nicecontrolcenter.Borders.Border b = e.getValue();
			if (!b.pregen || (!all && b.hard <= b.soft)) continue;
			JsonObject body = new JsonObject();
			body.addProperty("dimension", e.getKey());
			body.addProperty("x", b.x);
			body.addProperty("z", b.z);
			body.addProperty("radius", b.reach());
			body.addProperty("skipRadius", all ? 0 : b.soft);
			body.addProperty("shape", b.shape);
			body.addProperty("queue", true);
			body.addProperty("players", players == null ? "slow" : players);
			// Right after a start the mod may need a moment before it answers.
			String error = null;
			for (int attempt = 0; attempt < 12; attempt++) {
				error = m.post("/api/pregen/start", body);
				if (error == null || !error.startsWith("The mod doesn't answer")) break;
				Thread.sleep(5000);
			}
			if (error != null) return e.getKey() + ": " + error;
			started++;
		}
		if (started == 0) return all ? "No dimension is set to pregenerate (World tab)." : "No dimension has outer wilds to pregenerate (a hard border outside the soft one, with pregenerating on).";
		server.note("Task: pregenerating " + started + " dimension" + (started == 1 ? "" : "s"));
		return null;
	}

	/** Chat warnings before a restart: "Server restarts in 5 minutes." … "in 1 second". */
	private void countdown(Step step, String fallback, String now) throws InterruptedException {
		int total = step.minutes * 60;
		if (total <= 0) return;
		String template = step.text.isBlank() ? fallback : step.text;
		long end = System.currentTimeMillis() + total * 1000L;
		for (int at : WARN_AT) {
			if (at > total) continue;
			long wait = end - at * 1000L - System.currentTimeMillis();
			if (wait > 0) Thread.sleep(wait);
			if (!server.running()) return;
			server.quietCommand(say(template.replace("{time}", duration(at))));
		}
		long rest = end - System.currentTimeMillis();
		if (rest > 0) Thread.sleep(rest);
		server.quietCommand(say(now));
	}

	static String duration(int seconds) {
		if (seconds >= 60 && seconds % 60 == 0) {
			int m = seconds / 60;
			return m + (m == 1 ? " minute" : " minutes");
		}
		return seconds + (seconds == 1 ? " second" : " seconds");
	}

	private static final java.util.regex.Pattern VARIABLE = java.util.regex.Pattern.compile("\\{(player|online|clock|date)\\}");

	/**
	 * The tellraw command for a chat message. {player} becomes each reader's own name (the message is
	 * sent per player then), {online} everyone online, {clock} and {date} the panel's time; & codes
	 * (&a, &l …) color and format like in game. Gold unless a code says otherwise.
	 */
	String say(String template) {
		ZonedDateTime now = ZonedDateTime.now(zone);
		String text = template.replace("{clock}", String.format("%02d:%02d", now.getHour(), now.getMinute()))
				.replace("{date}", now.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)));
		text = text.replaceAll("&([0-9a-fk-or])", "\u00a7$1");
		com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
		JsonObject base = new JsonObject();
		base.addProperty("text", "");
		base.addProperty("color", "gold");
		parts.add(base);
		java.util.regex.Matcher m = VARIABLE.matcher(text);
		int at = 0;
		boolean perPlayer = false;
		while (m.find()) {
			if (m.start() > at) parts.add(text.substring(at, m.start()));
			JsonObject sel = new JsonObject();
			if (m.group(1).equals("player")) {
				sel.addProperty("selector", "@s");
				perPlayer = true;
			} else {
				sel.addProperty("selector", "@a");
			}
			parts.add(sel);
			at = m.end();
		}
		if (at < text.length()) parts.add(text.substring(at));
		return perPlayer ? "execute as @a run tellraw @s " + parts : "tellraw @a " + parts;
	}

	private static String describe(Step s) {
		return switch (s.type) {
			case "restart" -> s.minutes > 0 ? "restart countdown (" + s.minutes + " min)" : "restarting";
			case "backup" -> "making a backup";
			case "trim" -> s.minutes > 0 ? "outer wilds reset countdown (" + s.minutes + " min)" : "resetting the outer wilds";
			case "pregen" -> "starting pregeneration";
			case "cleanup" -> "cleaning up";
			case "maintenance" -> "switching maintenance " + ("off".equals(s.text) ? "off" : "on");
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
