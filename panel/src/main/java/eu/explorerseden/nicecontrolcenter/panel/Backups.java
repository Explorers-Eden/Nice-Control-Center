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
 * copy (save-off, save-all flush … save-on) so the world on disk is consistent. Region files go in with
 * their unused space blanked (see {@link #packedRegion}), other already-compressed files as they are, and
 * everything else is compressed quickly.
 */
public final class Backups {
	/** Settings in /data/panel/backups.json. */
	public static final class Settings {
		public List<String> exclude = new ArrayList<>(List.of("logs/**", "crash-reports/**", "debug/**", ".fabric/**", "libraries/**",
				"versions/**", "**/session.lock", "mods/.nice-control-center.download", DISTANT_HORIZONS, RESOURCE_PACKS));
		/** Which one-time changes to the defaults this file has had; null for files from before 1.14.0. */
		public Integer revision = REVISION;
		/** How many backups to keep (pinned ones and those an update still needs come on top). */
		public int keepLast = 10;
	}

	public record Entry(String name, long size, long time, String label, String by, boolean pinned, int files) {
	}

	private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Pattern NAME = Pattern.compile("backup-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-[a-z0-9-]{1,40})?\\.zip");
	private static final Pattern SAVED = Pattern.compile("Saved the game|Saved the world");
	private static final Set<String> STORED = Set.of("mca", "mcc", "gz", "zip", "jar", "png", "jpg", "ogg", "zst", "xz", "7z", "dat_old",
			"sqlite", "sqlite-wal", "sqlite-shm");
	/**
	 * Distant Horizons' level-of-detail databases: often more than half the world folder, hardly compressible,
	 * rebuilt from the world on their own, and copied mid-write they may not even open.
	 */
	static final String DISTANT_HORIZONS = "**/DistantHorizons.sqlite*";
	/** The mod's copies of the server resource pack that the panel hands out; downloaded again when missing. */
	static final String RESOURCE_PACKS = "nicecontrolcenter/resourcepack/**";
	private static final int REVISION = 3;
	/** Bigger region files than this (never seen in practice) are stored as they are. */
	private static final long PACK_MAX = 64L << 20;

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
	private volatile String lastCreated;
	/** Backups an operation still needs (an update's rollback point); retention leaves them alone. */
	private final Set<String> protectedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public Backups(Path serverDir, Path backupDir, Path settingsFile, Supervisor server, ZoneId zone) {
		this.serverDir = serverDir;
		this.backupDir = backupDir;
		this.settingsFile = settingsFile;
		this.server = server;
		this.zone = zone;
		Settings loaded = null;
		try {
			if (Files.exists(settingsFile)) {
				String text = Files.readString(settingsFile);
				loaded = Json.GSON.fromJson(text, Settings.class);
				// Gson keeps the field's default when the file has none, so look at the file itself.
				if (loaded != null && !com.google.gson.JsonParser.parseString(text).getAsJsonObject().has("revision")) loaded.revision = null;
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read " + settingsFile + ", using defaults: " + e.getMessage());
		}
		if (loaded != null && (loaded.revision == null || loaded.revision < REVISION)) {
			// Left out of backups once each (they can be taken out of the list again): Distant Horizons' data
			// (1.14.0) and the resource pack copies (1.15.0).
			if (loaded.exclude == null) loaded.exclude = new ArrayList<>();
			loaded.exclude = new ArrayList<>(loaded.exclude);
			int from = loaded.revision == null ? 0 : loaded.revision;
			if (from < 2 && !loaded.exclude.contains(DISTANT_HORIZONS)) loaded.exclude.add(DISTANT_HORIZONS);
			if (from < 3 && !loaded.exclude.contains(RESOURCE_PACKS)) loaded.exclude.add(RESOURCE_PACKS);
			loaded.revision = REVISION;
			try {
				Files.writeString(settingsFile, Json.GSON.toJson(loaded), StandardCharsets.UTF_8);
			} catch (IOException e) {
				System.err.println("Could not update " + settingsFile + ": " + e.getMessage());
			}
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
		if (next.keepLast < 1 || next.keepLast > 1000) return "Keep at least 1 and at most 1000 backups.";
		next.revision = REVISION;
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

	/** File name of the newest backup this panel made. */
	public String lastCreated() {
		return lastCreated;
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
		return create(label, by, true);
	}

	public void protect(String name) {
		if (name != null) protectedNames.add(name);
	}

	public void unprotect(String name) {
		if (name != null) protectedNames.remove(name);
	}

	private volatile Notifier notifier = Notifier.NONE;

	void useNotifier(Notifier n) {
		notifier = n;
	}

	/** prune = false for safety backups during a restore, so retention can't remove what's about to be restored. */
	public String create(String label, String by, boolean prune) {
		synchronized (this) {
			if (running) return "A backup is already running.";
			running = true;
		}
		String error = createNow(label, by, prune);
		if (error != null) notifier.alert("backupFailed", Map.of("message", error));
		return error;
	}

	private String createNow(String label, String by, boolean prune) {
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
					byte[] packed = extension(rel).equals("mca") ? packedRegion(file) : null;
					if (packed != null) {
						try {
							zip.setMethod(ZipOutputStream.DEFLATED);
							zip.putNextEntry(entry);
							zip.write(packed);
							zip.closeEntry();
						} catch (java.nio.file.NoSuchFileException e) {
							// Gone meanwhile; skip it.
						}
						filesDone++;
						continue;
					}
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
			lastCreated = name;
			long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
			lastResult = name + " (" + human(Files.size(backupDir.resolve(name))) + ", " + files.size() + " files, " + seconds + " s)";
			server.note("Backup done: " + lastResult);
			if (prune) prune();
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
				// The file explorer's trash and upload temp files never belong in a backup.
				String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
				if (dir.getParent() != null && dir.getParent().equals(serverDir) && (name.equals(ServerFiles.TRASH) || name.equals(ServerFiles.TMP))) {
					return FileVisitResult.SKIP_SUBTREE;
				}
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

	/**
	 * A region file (.mca: blocks, entities or points of interest) with everything the game doesn't read
	 * set to zero: the space after each chunk up to its next 4 KB sector, and sectors no chunk uses any more.
	 * Both still hold old chunk data, which doesn't compress; zeros do, so the backup gets about a third
	 * smaller, and the file is still a valid region file that the game reads exactly as before. Null when the
	 * file doesn't look like a region file, so it goes in byte for byte.
	 */
	static byte[] packedRegion(Path file) {
		try {
			long size = Files.size(file);
			if (size < 8192 || size > PACK_MAX) return null;
			byte[] in = Files.readAllBytes(file);
			byte[] out = new byte[in.length];
			// The location and timestamp tables.
			System.arraycopy(in, 0, out, 0, 8192);
			for (int i = 0; i < 1024; i++) {
				int loc = readInt(in, i * 4);
				if (loc == 0) continue;
				long start = (long) (loc >>> 8) * 4096;
				long sectors = (long) (loc & 0xFF) * 4096;
				if (start < 8192 || start >= in.length) continue;
				long end = Math.min(in.length, start + Math.max(sectors, 4096));
				// [length][compression][data]: keep exactly that when the length makes sense, else all its sectors.
				if (start + 4 <= in.length) {
					long length = readInt(in, (int) start) & 0xFFFFFFFFL;
					if (length >= 1 && start + 4 + length <= end) end = start + 4 + length;
				}
				System.arraycopy(in, (int) start, out, (int) start, (int) (end - start));
			}
			return out;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static int readInt(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
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

	/** Keeps the newest N backups; pinned ones and those an operation still needs stay on top of that. */
	public List<String> prune() {
		Settings s = settings;
		List<Entry> all = list();
		Set<String> keep = new HashSet<>();
		int kept = 0;
		for (Entry e : all) {
			if (e.pinned() || protectedNames.contains(e.name())) keep.add(e.name());
			else if (kept < s.keepLast) {
				keep.add(e.name());
				kept++;
			}
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
	 * folder is replaced as a whole, so files added since the backup disappear. safety: back up the
	 * current state first.
	 */
	/** Why a restore can't start right now, or null. */
	public String restoreBlocked() {
		if (server.running() || server.state() == Supervisor.State.STOPPING) return "Stop the server before restoring a backup.";
		if (running) return "A backup is already running.";
		return null;
	}

	public String restore(String name, List<String> only, String by) {
		return restore(name, only, by, false);
	}

	public String restore(String name, List<String> only, String by, boolean safety) {
		String blocked = restoreBlocked();
		if (blocked != null) return lastError = blocked;
		if (!exists(name)) return lastError = "No such backup.";
		// A crashed server may have an automatic restart pending; it must not start on a half-restored world.
		if (server.state() == Supervisor.State.CRASHED) server.stop();
		server.lock("A backup is being restored; the server can start again when that's done.");
		try {
			return restoreLocked(name, only, by, safety);
		} finally {
			server.unlock();
		}
	}

	private String restoreLocked(String name, List<String> only, String by, boolean safety) {
		protect(name);
		String safetyError = safety ? create("before restore", by, false) : null;
		if (safetyError != null) {
			unprotect(name);
			return "The safety backup failed, so nothing was restored: " + safetyError;
		}
		synchronized (this) {
			if (running) {
				unprotect(name);
				return "A backup is already running.";
			}
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
			String why = e instanceof java.nio.file.NoSuchFileException ? "a file is missing (" + e.getMessage() + ")" : e.getMessage();
			lastError = "Restore failed: " + why + "." + (safety ? " The safety backup from just before is in the list." : "");
			server.note(lastError);
			return lastError;
		} finally {
			unprotect(name);
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
