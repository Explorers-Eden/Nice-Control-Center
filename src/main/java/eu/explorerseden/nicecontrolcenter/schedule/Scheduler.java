package eu.explorerseden.nicecontrolcenter.schedule;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParseException;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;

/**
 * Scheduled commands: tasks with one or more commands that run once, daily, on chosen weekdays or
 * every few minutes, on the server's clock. Runs missed while the server was off are skipped.
 * Kept in nicecontrolcenter/schedule.json.
 */
public final class Scheduler {
	/** One scheduled task as stored and shown. */
	public static final class Task {
		public String id;
		public String name = "";
		public boolean enabled = true;
		public List<String> commands = new ArrayList<>();
		/** once | daily | weekly | interval | after (another task finished) */
		public String repeat = "daily";
		/** "HH:MM", for once/daily/weekly. */
		public String time = "04:00";
		/** "YYYY-MM-DD", for once. */
		public String date = "";
		/** 1 = Monday … 7 = Sunday, for weekly. */
		public List<Integer> days = new ArrayList<>();
		/** Minutes between runs, for interval. */
		public int every = 60;
		/** For "after": the task this one follows, and how many seconds to wait once it's done. */
		public String afterId;
		public int afterDelay = 0;
		/** Seconds to wait between two commands (on top of any "wait" lines). */
		public int pause = 0;
		/** While running: what it's doing right now, e.g. "step 3 of 5 · waiting until 04:05". */
		public transient volatile String progress;
		public transient volatile long progressUntil;
		public long nextRun;
		public long lastRun;
		public boolean lastOk = true;
		public List<String> lastOutput = new ArrayList<>();
		public String note;
	}

	private static final int MAX_TASKS = 200;
	private static final int MAX_COMMANDS = 50;
	private static List<Task> tasks = new ArrayList<>();
	private static ScheduledExecutorService ticker;
	private static volatile MinecraftServer server;
	private static Path file;

	private Scheduler() {
	}

	public static ZoneId zone() {
		return ZoneId.systemDefault();
	}

	// ── Lifecycle ───────────────────────────────────────────────────────────

	public static synchronized void start(MinecraftServer minecraft) {
		server = minecraft;
		file = minecraft.getServerDirectory().resolve("nicecontrolcenter").resolve("schedule.json");
		load();
		long now = System.currentTimeMillis();
		for (Task task : tasks) {
			// Don't catch up on runs missed while the server was off.
			if (task.enabled && task.nextRun > 0 && task.nextRun < now - 60_000) {
				if (task.repeat.equals("once")) {
					task.enabled = false;
					task.note = "Missed: the server wasn't running at that time.";
				}
				task.nextRun = 0;
			}
			plan(task, now);
		}
		save();
		ticker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Nice Control Center Scheduler");
			thread.setDaemon(true);
			return thread;
		});
		ticker.scheduleAtFixedRate(Scheduler::tick, 1, 1, TimeUnit.SECONDS);
	}

	public static synchronized void stop() {
		if (ticker != null) {
			ticker.shutdownNow();
			ticker = null;
		}
		server = null;
		// Chains still waiting for their next step end here; say so in their last run.
		for (Task task : tasks) {
			if (task.progress != null) {
				List<String> output = new ArrayList<>(task.lastOutput);
				output.add("Stopped: the server shut down during the task.");
				task.lastOutput = output;
				task.lastRun = System.currentTimeMillis();
				task.lastOk = false;
				task.progress = null;
				task.progressUntil = 0;
			}
		}
		save();
	}

	private static void tick() {
		MinecraftServer current = server;
		if (current == null) {
			return;
		}
		long now = System.currentTimeMillis();
		List<Task> due = new ArrayList<>();
		synchronized (Scheduler.class) {
			for (Task task : tasks) {
				if (task.enabled && task.nextRun > 0 && task.nextRun <= now && task.progress == null) {
					due.add(task);
					task.nextRun = -1; // Running; planned again afterwards.
				}
			}
		}
		for (Task task : due) {
			current.execute(() -> run(current, task, false));
		}
	}

	/** "wait 30s", "wait 5m", "wait 1h" or "wait 10" (seconds): the pause in ms, or -1 if it isn't a wait line. */
	static long waitMillis(String line) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)wait\\s+(\\d{1,6})\\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hours?)?")
				.matcher(line.strip());
		if (!m.matches()) {
			return -1;
		}
		long n = Long.parseLong(m.group(1));
		String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase(java.util.Locale.ROOT);
		return n * (unit.startsWith("h") ? 3_600_000L : unit.startsWith("m") ? 60_000L : 1000L);
	}

	private static List<String> steps(Task task) {
		return task.commands.stream().map(String::strip).filter(c -> !c.isEmpty() && !c.startsWith("#")).toList();
	}

	/**
	 * Runs a task's steps one after another: commands on the server thread, "wait" lines and the
	 * task's pause on the scheduler thread in between. Plans the next run when it's done; a manual
	 * run ({@code manual}) leaves the plan as it was.
	 */
	private static void run(MinecraftServer minecraft, Task task, boolean manual) {
		List<String> steps = steps(task);
		List<String> output = new ArrayList<>();
		long planned = task.nextRun;
		boolean enabled = task.enabled;
		task.progress = "starting";
		task.lastOutput = output;
		step(minecraft, task, steps, 0, output, new boolean[] {true}, manual, planned, enabled);
	}

	private static void step(MinecraftServer minecraft, Task task, List<String> steps, int index, List<String> output, boolean[] ok,
			boolean manual, long planned, boolean enabled) {
		if (index >= steps.size()) {
			finish(task, output, ok[0], manual, planned, enabled, null);
			return;
		}
		String line = steps.get(index);
		String counter = "step " + (index + 1) + " of " + steps.size();
		long wait = waitMillis(line);
		if (wait < 0) {
			try {
				ConsoleRunner.Result result = ConsoleRunner.runChecked(minecraft, line, "Scheduled task '" + task.name + "'");
				ok[0] &= result.ok();
				output.add((result.ok() ? "✓ /" : "✗ /") + (line.startsWith("/") ? line.substring(1) : line)
						+ (result.lines().isEmpty() ? "" : " → " + String.join(" | ", result.lines())));
			} catch (RuntimeException e) {
				ok[0] = false;
				output.add("✗ /" + line + " → " + e.getMessage());
			}
			// The task's pause goes between two commands, not before or after a wait line.
			boolean nextIsCommand = index + 1 < steps.size() && waitMillis(steps.get(index + 1)) < 0;
			wait = nextIsCommand ? Math.max(0, task.pause) * 1000L : 0;
		} else {
			output.add("⏸ " + line);
		}
		if (wait <= 0) {
			step(minecraft, task, steps, index + 1, output, ok, manual, planned, enabled);
			return;
		}
		ScheduledExecutorService current = ticker;
		if (current == null || server == null) {
			finish(task, output, false, manual, planned, enabled, "Stopped: the server shut down during the task.");
			return;
		}
		task.progress = counter + " done, waiting";
		task.progressUntil = System.currentTimeMillis() + wait;
		try {
			current.schedule(() -> minecraft.execute(() -> step(minecraft, task, steps, index + 1, output, ok, manual, planned, enabled)),
					wait, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			finish(task, output, false, manual, planned, enabled, "Stopped: the server shut down during the task.");
		}
	}

	private static void finish(Task task, List<String> output, boolean ok, boolean manual, long planned, boolean enabled, String note) {
		synchronized (Scheduler.class) {
			if (note != null) {
				output.add(note);
			}
			task.progress = null;
			task.progressUntil = 0;
			task.lastRun = System.currentTimeMillis();
			task.lastOk = ok && note == null;
			task.lastOutput = output.size() > 80 ? new ArrayList<>(output.subList(0, 80)) : new ArrayList<>(output);
			task.note = note;
			if (manual) {
				// A manual run doesn't use up a one-time task or shift the plan.
				task.enabled = enabled;
				task.nextRun = task.repeat.equals("interval") && enabled ? 0 : planned;
				plan(task, System.currentTimeMillis() + 1000);
			} else if (task.repeat.equals("once")) {
				task.enabled = false;
				task.nextRun = 0;
			} else {
				task.nextRun = 0;
				plan(task, System.currentTimeMillis() + 1000);
			}
			// Start the tasks chained after this one (unless the server is shutting down).
			if (note == null) {
				long now = System.currentTimeMillis();
				for (Task next : tasks) {
					if (next.enabled && "after".equals(next.repeat) && task.id.equals(next.afterId) && next.progress == null) {
						next.nextRun = now + Math.max(0, next.afterDelay) * 1000L;
						next.note = null;
					}
				}
			}
			save();
		}
	}

	// ── Planning ────────────────────────────────────────────────────────────

	/** Sets {@code nextRun} if it isn't planned yet. */
	private static void plan(Task task, long after) {
		if (!task.enabled) {
			task.nextRun = 0;
			return;
		}
		if (task.nextRun > after) {
			return;
		}
		task.nextRun = next(task, after);
	}

	/** The next run after {@code after} (epoch ms), or 0 if there is none. */
	static long next(Task task, long after) {
		ZoneId zone = zone();
		ZonedDateTime from = Instant.ofEpochMilli(after).atZone(zone);
		LocalTime time = parseTime(task.time);
		switch (task.repeat) {
			case "after" -> {
				return 0;
			}
			case "interval" -> {
				return after + Math.max(1, task.every) * 60_000L;
			}
			case "once" -> {
				try {
					ZonedDateTime at = LocalDateTime.of(LocalDate.parse(task.date), time).atZone(zone);
					return at.isAfter(from) ? at.toInstant().toEpochMilli() : 0;
				} catch (DateTimeParseException e) {
					return 0;
				}
			}
			default -> {
				for (int day = 0; day <= 7; day++) {
					ZonedDateTime candidate = from.toLocalDate().plusDays(day).atTime(time).atZone(zone);
					if (!candidate.isAfter(from)) {
						continue;
					}
					if (task.repeat.equals("weekly") && !task.days.contains(candidate.getDayOfWeek().getValue())) {
						continue;
					}
					return candidate.toInstant().toEpochMilli();
				}
				return 0;
			}
		}
	}

	private static LocalTime parseTime(String text) {
		try {
			return LocalTime.parse(text == null ? "" : text.strip());
		} catch (DateTimeParseException e) {
			return LocalTime.of(4, 0);
		}
	}

	// ── Editing (dashboard) ─────────────────────────────────────────────────

	public static synchronized List<Task> tasks() {
		List<Task> copy = new ArrayList<>(tasks);
		copy.sort(Comparator.comparing((Task t) -> !t.enabled).thenComparingLong(t -> t.nextRun <= 0 ? Long.MAX_VALUE : t.nextRun));
		return copy;
	}

	/** Adds or replaces a task (by id). Returns an error or null. */
	public static synchronized String save(Task incoming, String who) {
		String error = validate(incoming);
		if (error != null) {
			return error;
		}
		Task existing = incoming.id == null ? null : tasks.stream().filter(t -> t.id.equals(incoming.id)).findFirst().orElse(null);
		Task task = existing != null ? existing : new Task();
		if (existing == null) {
			if (tasks.size() >= MAX_TASKS) {
				return "There are already " + MAX_TASKS + " tasks.";
			}
			task.id = UUID.randomUUID().toString().substring(0, 8);
			tasks.add(task);
		}
		task.name = incoming.name.strip();
		task.enabled = incoming.enabled;
		task.commands = incoming.commands.stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
		task.commands = new ArrayList<>(task.commands);
		task.repeat = incoming.repeat;
		task.time = incoming.time == null ? "04:00" : incoming.time.strip();
		task.date = incoming.date == null ? "" : incoming.date.strip();
		task.days = incoming.days == null ? new ArrayList<>() : new ArrayList<>(incoming.days);
		task.every = incoming.every;
		task.pause = incoming.pause;
		task.afterId = "after".equals(incoming.repeat) ? incoming.afterId : null;
		task.afterDelay = incoming.afterDelay;
		task.note = null;
		task.nextRun = 0;
		plan(task, System.currentTimeMillis());
		if (task.enabled && task.nextRun == 0 && task.repeat.equals("once")) {
			task.note = "That date and time has already passed.";
		}
		save();
		NiceControlCenter.LOGGER.info("Scheduled task '{}' {} by {}", task.name, existing == null ? "created" : "changed", who);
		return null;
	}

	private static String validate(Task task) {
		if (task == null || task.name == null || task.name.isBlank() || task.name.length() > 80) {
			return "Give the task a name (up to 80 characters).";
		}
		if (task.commands == null || task.commands.stream().noneMatch(c -> !c.isBlank() && !c.strip().startsWith("#"))) {
			return "Add at least one command.";
		}
		if (task.commands.size() > MAX_COMMANDS || task.commands.stream().anyMatch(c -> c.length() > 32_000)) {
			return "Too many or too long commands (up to " + MAX_COMMANDS + ").";
		}
		if (!List.of("once", "daily", "weekly", "interval", "after").contains(task.repeat)) {
			return "Pick how often it repeats.";
		}
		if (task.repeat.equals("after")) {
			Task before = task.afterId == null ? null : tasks.stream().filter(t -> t.id.equals(task.afterId)).findFirst().orElse(null);
			if (before == null) {
				return "Pick the task this one runs after.";
			}
			if (before.id.equals(task.id)) {
				return "A task can't run after itself.";
			}
			// Follow the chain backwards; reaching this task again would loop forever.
			Task step = before;
			for (int i = 0; step != null && i <= tasks.size(); i++) {
				if (task.id != null && step.id.equals(task.id)) {
					return "That would make a loop: \"" + before.name + "\" already runs after this task.";
				}
				String up = "after".equals(step.repeat) ? step.afterId : null;
				step = up == null ? null : tasks.stream().filter(t -> t.id.equals(up)).findFirst().orElse(null);
			}
			if (task.afterDelay < 0 || task.afterDelay > 7 * 86400) {
				return "The delay after the other task can be 0 seconds to 7 days.";
			}
		}
		if (!task.repeat.equals("interval") && !task.repeat.equals("after")) {
			try {
				LocalTime.parse(task.time == null ? "" : task.time.strip());
			} catch (DateTimeParseException e) {
				return "Pick a time (HH:MM).";
			}
		}
		if (task.repeat.equals("once")) {
			try {
				LocalDate.parse(task.date == null ? "" : task.date.strip());
			} catch (DateTimeParseException e) {
				return "Pick a date.";
			}
		}
		if (task.repeat.equals("weekly") && (task.days == null || task.days.isEmpty() || task.days.stream().anyMatch(d -> d < 1 || d > 7))) {
			return "Pick at least one weekday.";
		}
		if (task.repeat.equals("interval") && (task.every < 1 || task.every > 60 * 24 * 31)) {
			return "Repeat every 1 minute to 31 days.";
		}
		if (task.pause < 0 || task.pause > 3600) {
			return "The pause between commands can be 0 to 3600 seconds.";
		}
		long waits = 0;
		for (String line : task.commands) {
			String clean = line.strip();
			if (clean.toLowerCase(java.util.Locale.ROOT).startsWith("wait")) {
				long ms = waitMillis(clean);
				if (ms < 0) {
					return "\"" + clean + "\" isn't a valid wait; use e.g. wait 30s, wait 5m or wait 1h.";
				}
				waits += ms;
			}
		}
		if (steps(task).stream().allMatch(c -> waitMillis(c) >= 0)) {
			return "Add at least one command besides the waits.";
		}
		if (waits > 24 * 3_600_000L) {
			return "The waits add up to more than 24 hours.";
		}
		return null;
	}

	public static synchronized String delete(String id, String who) {
		Task task = tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
		if (task == null) {
			return "Unknown task.";
		}
		tasks.remove(task);
		for (Task follower : tasks) {
			if ("after".equals(follower.repeat) && task.id.equals(follower.afterId)) {
				follower.note = "The task it ran after (\"" + task.name + "\") was deleted; pick another one.";
				follower.afterId = null;
			}
		}
		save();
		NiceControlCenter.LOGGER.info("Scheduled task '{}' deleted by {}", task.name, who);
		return null;
	}

	public static synchronized String setEnabled(String id, boolean on) {
		Task task = tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
		if (task == null) {
			return "Unknown task.";
		}
		task.enabled = on;
		task.nextRun = 0;
		task.note = null;
		plan(task, System.currentTimeMillis());
		if (on && task.nextRun == 0 && task.repeat.equals("once")) {
			task.note = "That date and time has already passed.";
		}
		save();
		return null;
	}

	/** Runs a task right away, without changing its plan. */
	public static String runNow(String id, String who) {
		MinecraftServer current = server;
		Task task;
		synchronized (Scheduler.class) {
			task = tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
		}
		if (task == null || current == null) {
			return "Unknown task.";
		}
		if (task.progress != null) {
			return "\"" + task.name + "\" is still running.";
		}
		NiceControlCenter.LOGGER.info("Scheduled task '{}' run now by {}", task.name, who);
		task.progress = "starting";
		current.execute(() -> run(current, task, true));
		return null;
	}

	// ── Saving ──────────────────────────────────────────────────────────────

	private static void load() {
		tasks = new ArrayList<>();
		if (file == null || !Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Task[] loaded = Json.GSON.fromJson(reader, Task[].class);
			if (loaded != null) {
				for (Task task : loaded) {
					if (task != null && task.id != null) {
						if (task.commands == null) {
							task.commands = new ArrayList<>();
						}
						if (task.days == null) {
							task.days = new ArrayList<>();
						}
						if (task.lastOutput == null) {
							task.lastOutput = new ArrayList<>();
						}
						tasks.add(task);
					}
				}
			}
		} catch (IOException | JsonParseException e) {
			NiceControlCenter.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("schedule.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp)) {
				new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(tasks, writer);
			}
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the schedule", e);
		}
	}

	/** Summary for the dashboard. */
	public static Map<String, Object> summary(boolean editable) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("editable", editable);
		result.put("zone", zone().getId());
		result.put("now", System.currentTimeMillis());
		result.put("today", DayOfWeek.from(LocalDate.now(zone())).getValue());
		List<Map<String, Object>> list = new ArrayList<>();
		for (Task task : tasks()) {
			Map<String, Object> t = Json.GSON.fromJson(Json.GSON.toJson(task), Map.class);
			t.put("progress", task.progress);
			t.put("progressUntil", task.progressUntil);
			list.add(t);
		}
		result.put("tasks", list);
		return result;
	}
}
