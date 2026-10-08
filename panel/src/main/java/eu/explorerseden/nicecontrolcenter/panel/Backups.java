package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Zip backups of the server folder in /data/backups. While the server runs, saving is paused for the
 * copy (save-off, save-all flush … save-on) so the world on disk is consistent. Region files and other
 * already-compressed files are stored as they are; everything else is compressed quickly.
 */
public final class Backups {
	/** Settings in /data/panel/backups.json. */
	public static final class Settings {
		public List<String> exclude = new ArrayList<>(List.of("logs/**", "crash-reports/**", "debug/**", ".fabric/**", "libraries/**",
				"versions/**", "**/session.lock", "mods/.nice-control-center.download"));
		public int keepLast = 10;
		public int keepDaily = 7;
		public int keepWeekly = 4;
	}

	public record Entry(String name, long size, long time, String label, String by, boolean pinned, int files) {
	}

	private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Pattern NAME = Pattern.compile("backup-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-[a-z0-9-]{1,40})?\\.zip");
	private static final Pattern SAVED = Pattern.compile("Saved the game|Saved the world");
	private static final Set<String> STORED = Set.of("mca", "mcc", "gz", "zip", "jar", "png", "jpg", "ogg", "zst", "xz", "7z", "dat_old");

	private final Path serverDir;
	private final Path backupDir;
	private final Path settingsFile;
	private final Supervisor server;
	private final ZoneId zone;
	private volatile Settings settings;

	private volatile boolean running;
	private volatile String phase = "";
	private volatile long filesDone;
	private volatile long filesTotal;
	private volatile String lastError;
	private volatile String lastResult;

	public Backups(Path serverDir, Path backupDir, Path settingsFile, Supervisor server, ZoneId zone) {
		this.serverDir = serverDir;
		this.backupDir = backupDir;
		this.settingsFile = settingsFile;
		this.server = server;
		this.zone = zone;
		Settings loaded = null;
		try {
			if (Files.exists(settingsFile)) loaded = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read " + settingsFile + ", using defaults: " + e.getMessage());
		}
		settings = loaded != null ? loaded : new Settings();
	}

	public Settings settings() {
		return settings;
	}

	/** Returns an error, or null. */
	public String saveSettings(Settings next) throws IOException {
		if (next.exclude == null) next.exclude = new ArrayList<>();
		next.exclude = next.exclude.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
		for (String glob : next.exclude) {
			try {
				FileSystems.getDefault().getPathMatcher("glob:" + glob);
			} catch (RuntimeException e) {
				return "Not a valid pattern: " + glob;
			}
		}
		if (next.keepLast < 1 || next.keepLast > 1000) return "Keep at least 1 and at most 1000 recent backups.";
		if (next.keepDaily < 0 || next.keepDaily > 365 || next.keepWeekly < 0 || next.keepWeekly > 520) return "Daily and weekly counts must be 0–365 and 0–520.";
		Files.createDirectories(settingsFile.getParent());
		Path tmp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.toJson(next), StandardCharsets.UTF_8);
		Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		settings = next;
		return null;
	}

	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", running);
		out.put("phase", phase);
		out.put("filesDone", filesDone);
		out.put("filesTotal", filesTotal);
		out.put("lastError", lastError);
		out.put("lastResult", lastResult);
		try {
			Files.createDirectories(backupDir);
			FileStore store = Files.getFileStore(backupDir);
			out.put("freeBytes", store.getUsableSpace());
			out.put("totalBytes", store.getTotalSpace());
		} catch (IOException e) {
			out.put("freeBytes", 0);
		}
		return out;
	}

	public boolean busy() {
		return running;
	}

	// ── Creating ───────────────────────────────────────────────────────────

	/**
	 * Makes a backup now, in the calling thread. Returns an error message, or null. label: a short
	 * reason ("manual", "scheduled", "before restore"); by: who asked for it.
	 */
	public String create(String label, String by) {
		synchronized (this) {
			if (running) return "A backup is already running.";
			running = true;
		}
		lastError = null;
		long started = System.currentTimeMillis();
		boolean savingPaused = false;
		Path tmp = null;
		try {
			Files.createDirectories(backupDir);
			String slug = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
			if (slug.length() > 40) slug = slug.substring(0, 40);
			String name = "backup-" + LocalDateTime.now(zone).format(NAME_TIME) + (slug.isEmpty() ? "" : "-" + slug) + ".zip";
			if (server.running()) {
				phase = "Saving the world";
				server.note("Backup: pausing saves and flushing the world to disk");
				savingPaused = server.quietCommand("save-off");
				if (!server.commandAndWait("save-all flush", SAVED, 120_000)) {
					server.note("Backup: the server didn't confirm the save within 2 minutes; backing up what's on disk");
				}
			}
			phase = "Counting files";
			List<Path> files = collect();
			filesTotal = files.size();
			filesDone = 0;
			phase = "Writing " + name;
			tmp = backupDir.resolve(name + ".partial");
			try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
				zip.setLevel(Deflater.BEST_SPEED);
				byte[] buffer = new byte[1 << 16];
				for (Path file : files) {
					String rel = serverDir.relativize(file).toString().replace('\\', '/');
					ZipEntry entry = new ZipEntry(rel);
					entry.setTime(Files.getLastModifiedTime(file).toMillis());
					boolean store = STORED.contains(extension(rel));
					zip.setMethod(store ? ZipOutputStream.STORED : ZipOutputStream.DEFLATED);
					if (store) {
						// STORED entries need size and CRC up front.
						long size = Files.size(file);
						java.util.zip.CRC32 crc = new java.util.zip.CRC32();
						try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
							int n;
							while ((n = in.read(buffer)) > 0) crc.update(buffer, 0, n);
						}
						entry.setSize(size);
						entry.setCompressedSize(size);
						entry.setCrc(crc.getValue());
					}
					try {
						zip.putNextEntry(entry);
						try (InputStream in = Files.newInputStream(file)) {
							in.transferTo(zip);
						}
						zip.closeEntry();
					} catch (java.nio.file.NoSuchFileException e) {
						// Deleted while we were copying (a temp file); skip it.
					}
					filesDone++;
				}
			}
			if (savingPaused) {
				server.quietCommand("save-on");
				savingPaused = false;
			}
			Files.move(tmp, backupDir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
			tmp = null;
			writeMeta(name, label, by, false, files.size());
			long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
			lastResult = name + " (" + human(Files.size(backupDir.resolve(name))) + ", " + files.size() + " files, " + seconds + " s)";
			server.note("Backup done: " + lastResult);
			prune();
			return null;
		} catch (IOException | InterruptedException | RuntimeException e) {
			lastError = "Backup failed: " + e.getMessage();
			server.note(lastError);
			return lastError;
		} finally {
			if (savingPaused) server.quietCommand("save-on");
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// Left for the next prune.
				}
			}
			phase = "";
			running = false;
		}
	}

	private List<Path> collect() throws IOException {
		List<PathMatcher> excludes = settings.exclude.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
		// "logs/**" matches everything below logs, so the folder can be skipped as a whole.
		List<PathMatcher> folders = settings.exclude.stream().filter(g -> g.endsWith("/**"))
				.map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g.substring(0, g.length() - 3))).toList();
		Path backupsInside = backupDir.toAbsolutePath().normalize();
		List<Path> out = new ArrayList<>();
		Files.walkFileTree(serverDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (dir.toAbsolutePath().normalize().equals(backupsInside)) return FileVisitResult.SKIP_SUBTREE;
				if (!dir.equals(serverDir) && excluded(folders, serverDir.relativize(dir))) return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && !excluded(excludes, serverDir.relativize(file))) out.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				return FileVisitResult.CONTINUE;
			}
		});
		return out;
	}

	private static boolean excluded(List<PathMatcher> matchers, Path rel) {
		for (PathMatcher m : matchers) {
			if (m.matches(rel)) return true;
		}
		return false;
	}

	// ── Listing, retention, metadata ───────────────────────────────────────

	public List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		if (!Files.isDirectory(backupDir)) return out;
		try (Stream<Path> files = Files.list(backupDir)) {
			for (Path f : files.filter(f -> NAME.matcher(f.getFileName().toString()).matches()).toList()) {
				Map<?, ?> meta = readMeta(f.getFileName().toString());
				out.add(new Entry(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toMillis(),
						meta.get("label") instanceof String s ? s : "", meta.get("by") instanceof String s ? s : "",
						Boolean.TRUE.equals(meta.get("pinned")), meta.get("files") instanceof Number n ? n.intValue() : 0));
			}
		} catch (IOException e) {
			// Empty list.
		}
		out.sort(Comparator.comparingLong(Entry::time).reversed());
		return out;
	}

	/** Grandfather-father-son: the newest N, the newest per day for D days, per week for W weeks; pinned ones stay. */
	public List<String> prune() {
		Settings s = settings;
		List<Entry> all = list();
		Set<String> keep = new HashSet<>();
		for (int i = 0; i < Math.min(s.keepLast, all.size()); i++) keep.add(all.get(i).name());
		Set<LocalDate> days = new LinkedHashSet<>();
		Set<String> weeks = new LinkedHashSet<>();
		for (Entry e : all) {
			if (e.pinned()) keep.add(e.name());
			LocalDate day = Instant.ofEpochMilli(e.time()).atZone(zone).toLocalDate();
			if (days.size() < s.keepDaily && days.add(day)) keep.add(e.name());
			String week = day.get(IsoFields.WEEK_BASED_YEAR) + "-" + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
			if (weeks.size() < s.keepWeekly && weeks.add(week)) keep.add(e.name());
		}
		List<String> deleted = new ArrayList<>();
		for (Entry e : all) {
			if (keep.contains(e.name())) continue;
			try {
				Files.deleteIfExists(backupDir.resolve(e.name()));
				Files.deleteIfExists(metaFile(e.name()));
				deleted.add(e.name());
			} catch (IOException ex) {
				// Next time.
			}
		}
		if (!deleted.isEmpty()) server.note("Backup retention removed " + deleted.size() + " old backup" + (deleted.size() == 1 ? "" : "s"));
		return deleted;
	}

	public boolean exists(String name) {
		return name != null && NAME.matcher(name).matches() && Files.isRegularFile(backupDir.resolve(name));
	}

	public Path file(String name) {
		return exists(name) ? backupDir.resolve(name) : null;
	}

	public void delete(String name) throws IOException {
		if (!exists(name)) return;
		Files.deleteIfExists(backupDir.resolve(name));
		Files.deleteIfExists(metaFile(name));
	}

	public void pin(String name, boolean pinned) throws IOException {
		Map<?, ?> meta = readMeta(name);
		writeMeta(name, meta.get("label") instanceof String s ? s : "", meta.get("by") instanceof String s ? s : "", pinned,
				meta.get("files") instanceof Number n ? n.intValue() : 0);
	}

	private Path metaFile(String name) {
		return backupDir.resolve(name + ".json");
	}

	private void writeMeta(String name, String label, String by, boolean pinned, int files) throws IOException {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("label", label == null ? "" : label);
		meta.put("by", by == null ? "" : by);
		meta.put("pinned", pinned);
		meta.put("files", files);
		Files.writeString(metaFile(name), Json.GSON.toJson(meta), StandardCharsets.UTF_8);
	}

	private Map<?, ?> readMeta(String name) {
		try {
			Path f = metaFile(name);
			if (Files.exists(f)) {
				Map<?, ?> m = Json.GSON.fromJson(Files.readString(f), Map.class);
				if (m != null) return m;
			}
		} catch (IOException | RuntimeException e) {
			// No metadata.
		}
		return Map.of();
	}

	// ── Restoring ──────────────────────────────────────────────────────────

	/** The top-level files and folders in a backup, to choose what to restore. */
	public List<Map<String, Object>> contents(String name) throws IOException {
		Map<String, long[]> top = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(backupDir.resolve(name).toFile())) {
			zip.stream().forEach(e -> {
				String n = e.getName();
				int slash = n.indexOf('/');
				String key = slash < 0 ? n : n.substring(0, slash + 1);
				long[] stat = top.computeIfAbsent(key, k -> new long[2]);
				stat[0]++;
				stat[1] += Math.max(0, e.getSize());
			});
		}
		List<Map<String, Object>> out = new ArrayList<>();
		new TreeSet<>(top.keySet()).forEach(k -> out.add(Map.of("path", k, "files", top.get(k)[0], "bytes", top.get(k)[1])));
		return out;
	}

	/**
	 * Restores the chosen top-level entries (all when empty) while the server is stopped. Each chosen
	 * folder is replaced as a whole, so files added since the backup disappear; a safety backup is made first.
	 */
	/** Why a restore can't start right now, or null. */
	public String restoreBlocked() {
		if (server.running() || server.state() == Supervisor.State.STOPPING) return "Stop the server before restoring a backup.";
		if (running) return "A backup is already running.";
		return null;
	}

	public String restore(String name, List<String> only, String by) {
		String blocked = restoreBlocked();
		if (blocked != null) return lastError = blocked;
		if (!exists(name)) return lastError = "No such backup.";
		String safety = create("before restore", by);
		if (safety != null) return "The safety backup failed, so nothing was restored: " + safety;
		synchronized (this) {
			if (running) return "A backup is already running.";
			running = true;
		}
		try {
			phase = "Restoring " + name;
			List<Map<String, Object>> top = contents(name);
			Set<String> chosen = new HashSet<>();
			for (Map<String, Object> t : top) {
				String p = (String) t.get("path");
				if (only == null || only.isEmpty() || only.contains(p)) chosen.add(p);
			}
			Path root = serverDir.toAbsolutePath().normalize();
			for (String p : chosen) {
				Path target = root.resolve(p).normalize();
				if (!target.startsWith(root) || target.equals(root)) continue;
				deleteRecursively(target);
			}
			try (ZipFile zip = new ZipFile(backupDir.resolve(name).toFile())) {
				var entries = zip.entries();
				filesTotal = zip.size();
				filesDone = 0;
				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();
					String n = e.getName();
					int slash = n.indexOf('/');
					String key = slash < 0 ? n : n.substring(0, slash + 1);
					filesDone++;
					if (!chosen.contains(key) || e.isDirectory()) continue;
					Path target = root.resolve(n).normalize();
					if (!target.startsWith(root)) continue;
					Files.createDirectories(target.getParent());
					try (InputStream in = zip.getInputStream(e); OutputStream out = Files.newOutputStream(target)) {
						in.transferTo(out);
					}
					if (e.getTime() > 0) Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(e.getTime()));
				}
			}
			lastResult = "Restored " + name + (only == null || only.isEmpty() ? "" : " (" + String.join(", ", chosen) + ")");
			server.note(lastResult);
			return null;
		} catch (IOException | RuntimeException e) {
			lastError = "Restore failed: " + e.getMessage() + ". The safety backup from just before is in the list.";
			server.note(lastError);
			return lastError;
		} finally {
			phase = "";
			running = false;
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> children = Files.list(path)) {
				for (Path child : children.toList()) deleteRecursively(child);
			}
		}
		Files.delete(path);
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	static String human(long bytes) {
		if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
		if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
		return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
	}
}
