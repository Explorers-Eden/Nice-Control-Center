package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Deletes old log files: per folder, files matching a pattern that are older than N days. Runs once a
 * day and on demand, with a preview. latest.log and files in use are never touched.
 */
public final class LogCleanup {
	public static final class Rule {
		public String folder;
		public String pattern = "*";
		public int maxAgeDays = 14;
		public boolean enabled = true;

		Rule() {
		}

		Rule(String folder, String pattern, int days) {
			this.folder = folder;
			this.pattern = pattern;
			this.maxAgeDays = days;
		}
	}

	public static final class Settings {
		public boolean daily = true;
		public List<Rule> rules = new ArrayList<>(List.of(
				new Rule("logs", "*.log.gz", 14),
				new Rule("crash-reports", "*.txt", 30),
				new Rule("debug", "**", 7)));
	}

	public record Candidate(String path, long size, long time) {
	}

	private final ServerFiles files;
	private final Path settingsFile;
	private final Audit audit;
	private final ZoneId zone;
	private volatile Settings settings;
	private volatile LocalDate lastRun;
	private volatile String lastResult;

	public LogCleanup(ServerFiles files, Path settingsFile, Audit audit, ZoneId zone) {
		this.files = files;
		this.settingsFile = settingsFile;
		this.audit = audit;
		this.zone = zone;
		Settings loaded = null;
		try {
			if (Files.exists(settingsFile)) loaded = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read " + settingsFile + ": " + e.getMessage());
		}
		settings = loaded != null ? loaded : new Settings();
	}

	public void start() {
		var timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "log-cleanup");
			t.setDaemon(true);
			return t;
		});
		// Once a day after 05:00, plus the trash of the file explorer.
		timer.scheduleAtFixedRate(() -> {
			try {
				LocalDate today = LocalDate.now(zone);
				if (java.time.LocalTime.now(zone).getHour() < 5 || today.equals(lastRun)) return;
				lastRun = today;
				files.emptyOldTrash(7);
				if (settings.daily) {
					List<Candidate> deleted = run();
					if (!deleted.isEmpty()) audit.log("panel", "files.cleanup", deleted.size() + " old log files", null);
				}
			} catch (RuntimeException e) {
				System.err.println("Log cleanup failed: " + e);
			}
		}, 1, 30, TimeUnit.MINUTES);
	}

	public Settings settings() {
		return settings;
	}

	public String lastResult() {
		return lastResult;
	}

	public String save(Settings next) throws IOException {
		if (next.rules == null) next.rules = new ArrayList<>();
		for (Rule r : next.rules) {
			if (r.folder == null || r.folder.isBlank()) return "Every rule needs a folder.";
			try {
				Path dir = files.resolve(r.folder);
				if (dir.equals(files.root())) return "A rule needs a folder inside the server folder, not the server folder itself.";
				if (inWorld(dir)) return "The world folder isn't a log folder; rules can't delete from it.";
				FileSystems.getDefault().getPathMatcher("glob:" + r.pattern);
			} catch (RuntimeException e) {
				return "Rule for " + r.folder + ": " + e.getMessage();
			}
			if (r.maxAgeDays < 1 || r.maxAgeDays > 3650) return "Ages are 1–3650 days.";
		}
		Files.createDirectories(settingsFile.getParent());
		Path tmp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.toJson(next), StandardCharsets.UTF_8);
		Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		settings = next;
		return null;
	}

	/** What a cleanup would delete right now. */
	public List<Candidate> preview() {
		List<Candidate> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Rule rule : settings.rules) {
			if (!rule.enabled) continue;
			Path dir;
			try {
				dir = files.resolve(rule.folder);
			} catch (IOException | RuntimeException e) {
				continue;
			}
			// Rules saved before these checks existed are skipped the same way.
			if (!Files.isDirectory(dir) || dir.equals(files.root()) || inWorld(dir)) continue;
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + rule.pattern);
			long cutoff = now - rule.maxAgeDays * 86_400_000L;
			try {
				Files.walkFileTree(dir, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						String name = file.getFileName().toString();
						if (!attrs.isRegularFile() || name.equals("latest.log") || name.equals("debug.log")) return FileVisitResult.CONTINUE;
						if (!matcher.matches(dir.relativize(file)) && !matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
						if (attrs.lastModifiedTime().toMillis() < cutoff) {
							out.add(new Candidate(files.rel(file), attrs.size(), attrs.lastModifiedTime().toMillis()));
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException e) {
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				// Skip this rule.
			}
		}
		return out;
	}

	/** Deletes what preview() lists; returns the deleted files. */
	public List<Candidate> run() {
		List<Candidate> deleted = new ArrayList<>();
		long bytes = 0;
		for (Candidate c : preview()) {
			try {
				Files.deleteIfExists(files.resolve(c.path()));
				deleted.add(c);
				bytes += c.size();
			} catch (IOException | RuntimeException e) {
				// In use or gone; next time.
			}
		}
		lastResult = deleted.isEmpty() ? "Nothing to delete" : "Deleted " + deleted.size() + " file" + (deleted.size() == 1 ? "" : "s") + " (" + Backups.human(bytes) + ")";
		return deleted;
	}

	private boolean inWorld(Path dir) {
		Path world = files.root().resolve(files.worldFolder()).normalize();
		return dir.startsWith(world) || world.startsWith(dir);
	}

	public Map<String, Object> view() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("settings", settings);
		out.put("preview", preview());
		out.put("lastResult", lastResult);
		return out;
	}
}
