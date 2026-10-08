package eu.explorerseden.nicecontrolcenter.panel;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * File operations inside the server folder for the file explorer and the config editor. Every path
 * is checked to stay inside the folder (also through symlinks). Deleted files go to .panel-trash for
 * 7 days. While the server runs, the world folder is read-only, so a running world can't be corrupted.
 */
public final class ServerFiles {
	public static final String TRASH = ".panel-trash";
	public static final String TMP = ".panel-tmp";
	public static final long MAX_TEXT = 5L * 1024 * 1024;
	private static final DateTimeFormatter TRASH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final Path root;
	private final Path rootReal;
	private final Supervisor server;

	public ServerFiles(Path serverDir, Supervisor server) throws IOException {
		this.root = serverDir.toAbsolutePath().normalize();
		Files.createDirectories(root);
		this.rootReal = root.toRealPath();
		this.server = server;
	}

	public Path root() {
		return root;
	}

	/** Resolves a path relative to the server folder; throws if it would leave the folder. */
	public Path resolve(String rel) throws IOException {
		String clean = rel == null ? "" : rel.replace('\\', '/').strip();
		while (clean.startsWith("/")) clean = clean.substring(1);
		Path p = root.resolve(clean).normalize();
		if (!p.startsWith(root)) throw new SecurityException("That path is outside the server folder.");
		Path existing = p;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
		if (existing != null && !existing.toRealPath().startsWith(rootReal)) throw new SecurityException("That path leads outside the server folder.");
		return p;
	}

	public String rel(Path p) {
		return root.relativize(p).toString().replace('\\', '/');
	}

	/** The world folder from server.properties (level-name), relative to the server folder. */
	public String worldFolder() {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(root.resolve("server.properties"))) {
			props.load(in);
		} catch (IOException e) {
			// Default.
		}
		String name = props.getProperty("level-name", "world").strip();
		return name.isEmpty() ? "world" : name;
	}

	/** Throws when a change at this path isn't allowed right now (the world while the server runs). */
	public void checkWritable(Path p) {
		if (p.equals(root)) throw new SecurityException("The server folder itself can't be changed.");
		if (server.running() || server.state() == Supervisor.State.STOPPING) {
			Path world = root.resolve(worldFolder()).normalize();
			if (p.startsWith(world) || world.startsWith(p)) {
				throw new SecurityException("The world folder can't be changed while the server runs. Stop the server first.");
			}
		}
	}

	// ── Reading ────────────────────────────────────────────────────────────

	public List<Map<String, Object>> list(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) throw new IOException("Not a folder.");
		List<Map<String, Object>> out = new ArrayList<>();
		try (Stream<Path> children = Files.list(dir)) {
			for (Path c : children.toList()) {
				BasicFileAttributes a = Files.readAttributes(c, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", c.getFileName().toString());
				m.put("dir", a.isDirectory());
				m.put("link", a.isSymbolicLink());
				m.put("size", a.isDirectory() ? 0 : a.size());
				m.put("time", a.lastModifiedTime().toMillis());
				out.add(m);
			}
		}
		out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("dir"))
				.thenComparing(m -> ((String) m.get("name")).toLowerCase()));
		return out;
	}

	/** The file as UTF-8 text, or null when it's binary or too big for the editor. */
	public String readText(Path file) throws IOException {
		if (!Files.isRegularFile(file)) throw new IOException("Not a file.");
		if (Files.size(file) > MAX_TEXT) return null;
		byte[] bytes = Files.readAllBytes(file);
		for (int i = 0; i < Math.min(bytes.length, 8192); i++) if (bytes[i] == 0) return null;
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}

	// ── Changing ───────────────────────────────────────────────────────────

	/** Writes text atomically; the old version stays next to it as <name>.bak. */
	public void writeText(Path file, String text, boolean keepBackup) throws IOException {
		checkWritable(file);
		Files.createDirectories(file.getParent());
		if (keepBackup && Files.isRegularFile(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
		Path tmp = file.resolveSibling("." + file.getFileName() + ".panel-tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public void mkdir(Path dir) throws IOException {
		checkWritable(dir);
		Files.createDirectories(dir);
	}

	public void move(Path from, Path to) throws IOException {
		checkWritable(from);
		checkWritable(to);
		if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException(rel(to) + " already exists.");
		if (to.startsWith(from)) throw new IOException("A folder can't be moved into itself.");
		Files.createDirectories(to.getParent());
		Files.move(from, to);
	}

	public void copy(Path from, Path to) throws IOException {
		checkWritable(to);
		if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException(rel(to) + " already exists.");
		if (to.startsWith(from)) throw new IOException("A folder can't be copied into itself.");
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!attrs.isSymbolicLink()) Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Moves to .panel-trash/<time>/<path>, emptied after 7 days. */
	public void trash(Path p) throws IOException {
		checkWritable(p);
		if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
		Path trashRoot = root.resolve(TRASH);
		if (p.startsWith(trashRoot)) {
			deleteRecursively(p);
			return;
		}
		Path target = trashRoot.resolve(LocalDateTime.now().format(TRASH_TIME)).resolve(rel(p));
		Files.createDirectories(target.getParent());
		Path free = target;
		for (int i = 2; Files.exists(free, LinkOption.NOFOLLOW_LINKS); i++) free = target.resolveSibling(target.getFileName() + " (" + i + ")");
		Files.move(p, free);
	}

	public int emptyOldTrash(int days) {
		Path trashRoot = root.resolve(TRASH);
		if (!Files.isDirectory(trashRoot)) return 0;
		Instant cutoff = Instant.now().minusSeconds(days * 86400L);
		int removed = 0;
		try (Stream<Path> batches = Files.list(trashRoot)) {
			for (Path batch : batches.toList()) {
				if (Files.getLastModifiedTime(batch).toInstant().isBefore(cutoff)) {
					deleteRecursively(batch);
					removed++;
				}
			}
		} catch (IOException e) {
			// Next time.
		}
		return removed;
	}

	public Path saveUpload(Path dir, String name, InputStream in, boolean overwrite) throws IOException {
		String clean = name == null ? "" : name.replace('\\', '/');
		clean = clean.substring(clean.lastIndexOf('/') + 1).strip();
		if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) throw new IOException("Bad file name.");
		Path target = resolve(rel(dir) + "/" + clean);
		checkWritable(target);
		if (!overwrite && Files.exists(target)) throw new IOException(clean + " already exists.");
		Path tmpDir = root.resolve(TMP);
		Files.createDirectories(tmpDir);
		Path tmp = Files.createTempFile(tmpDir, "upload", ".part");
		try {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.createDirectories(target.getParent());
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tmp);
		}
		return target;
	}

	/** Extracts a zip next to it; entries that would leave the folder are skipped. */
	public int unzip(Path zipFile, Path into) throws IOException {
		checkWritable(into);
		int count = 0;
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				Path target = into.resolve(e.getName()).normalize();
				if (!target.startsWith(into) || !target.startsWith(root)) continue;
				if (e.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				try (InputStream in = zip.getInputStream(e)) {
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
				if (e.getTime() > 0) Files.setLastModifiedTime(target, FileTime.fromMillis(e.getTime()));
				count++;
			}
		}
		return count;
	}

	/** Writes the files/folders as one zip, paths relative to base. */
	public void zip(List<Path> paths, Path base, OutputStream out) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16))) {
			zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
			for (Path p : paths) {
				Files.walkFileTree(p, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
						ZipEntry entry = new ZipEntry(base.relativize(file).toString().replace('\\', '/'));
						entry.setTime(attrs.lastModifiedTime().toMillis());
						zip.putNextEntry(entry);
						try (InputStream in = Files.newInputStream(file)) {
							in.transferTo(zip);
						} catch (IOException e) {
							// A locked or vanished file: leave it out.
						}
						zip.closeEntry();
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException e) {
						return FileVisitResult.CONTINUE;
					}
				});
			}
		}
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> children = Files.list(path)) {
				for (Path child : children.toList()) deleteRecursively(child);
			}
		}
		Files.delete(path);
	}
}
