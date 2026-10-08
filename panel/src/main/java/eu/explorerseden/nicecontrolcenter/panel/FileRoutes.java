package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** File explorer, config editor and log cleanup. */
final class FileRoutes {
	interface FileAction {
		void handle(Context ctx, Auth.Session me) throws Exception;
	}

	private final ServerFiles files;
	private final LogCleanup cleanup;
	private final Audit audit;

	FileRoutes(ServerFiles files, LogCleanup cleanup, Audit audit) {
		this.files = files;
		this.cleanup = cleanup;
		this.audit = audit;
	}

	/** files.read / files.write cover everything; files.config only config/. */
	private boolean allowed(Auth.Session me, Path p, boolean write) {
		if (me.can(write ? Permissions.FILES_WRITE : Permissions.FILES_READ)) return true;
		return me.can(Permissions.FILES_CONFIG) && p.startsWith(files.root().resolve("config"));
	}

	private Path path(Context ctx, Auth.Session me, String rel, boolean write) throws IOException {
		Path p = files.resolve(rel);
		if (!allowed(me, p, write)) throw new SecurityException("Your account isn't allowed to " + (write ? "change" : "see") + " " + files.rel(p) + ".");
		return p;
	}

	/** Any logged-in user; the action checks the path itself. Turns problems into readable errors. */
	private io.javalin.http.Handler action(FileAction action) {
		return guard(null, (ctx, me) -> {
			if (!me.can(Permissions.FILES_READ) && !me.can(Permissions.FILES_CONFIG)) {
				error(ctx, HttpStatus.FORBIDDEN, "Your account isn't allowed to use the files.");
				return;
			}
			try {
				action.handle(ctx, me);
			} catch (SecurityException e) {
				error(ctx, HttpStatus.FORBIDDEN, e.getMessage());
			} catch (java.nio.file.NoSuchFileException e) {
				error(ctx, HttpStatus.NOT_FOUND, "Not found: " + e.getMessage());
			} catch (java.nio.file.FileAlreadyExistsException e) {
				error(ctx, HttpStatus.CONFLICT, "Already exists: " + e.getMessage());
			} catch (IOException e) {
				error(ctx, HttpStatus.BAD_REQUEST, e.getMessage() == null ? e.toString() : e.getMessage());
			}
		});
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/files/list", action((ctx, me) -> {
			String rel = ctx.queryParam("path");
			Path dir = files.resolve(rel);
			if (!me.can(Permissions.FILES_READ)) dir = path(ctx, me, rel == null || rel.isBlank() ? "config" : rel, false);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("path", files.rel(dir));
			out.put("entries", files.list(dir));
			out.put("world", files.worldFolder());
			json(ctx, out);
		}));

		routes.get("/api/files/read", action((ctx, me) -> {
			Path file = path(ctx, me, ctx.queryParam("path"), false);
			String text = files.readText(file);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("path", files.rel(file));
			out.put("size", Files.size(file));
			out.put("time", Files.getLastModifiedTime(file).toMillis());
			out.put("text", text);
			out.put("format", ConfigCheck.format(file.getFileName().toString()));
			out.put("flatJson", text != null && ConfigCheck.strictJsonObjectOfScalars(text));
			json(ctx, out);
		}));

		routes.post("/api/files/write", action((ctx, me) -> {
			JsonObject body = body(ctx);
			Path file = path(ctx, me, str(body, "path"), true);
			String text = str(body, "text");
			boolean force = Boolean.TRUE.equals(bool(body, "force"));
			ConfigCheck.Problem problem = ConfigCheck.check(file.getFileName().toString(), text);
			if (problem != null && !force) {
				json(ctx.status(HttpStatus.UNPROCESSABLE_CONTENT), Map.of("error", problem.message(), "line", problem.line(), "column", problem.column()));
				return;
			}
			String before = Files.isRegularFile(file) ? files.readText(file) : "";
			files.writeText(file, text, true);
			audit.log(me.name(), "files.edit", files.rel(file) + " (" + changeSummary(before, text) + ")" + (problem != null ? ", saved despite: " + problem.message() : ""),
					clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.get("/api/files/download", action((ctx, me) -> {
			Path p = path(ctx, me, ctx.queryParam("path"), false);
			if (Files.isDirectory(p)) {
				String name = (p.equals(files.root()) ? "server" : p.getFileName().toString()) + ".zip";
				audit.log(me.name(), "files.download", files.rel(p) + "/ (as zip)", clientIp(ctx));
				ctx.header("Content-Disposition", "attachment; filename=\"" + name.replace("\"", "") + "\"");
				ctx.contentType("application/zip");
				ctx.result(zipStream(List.of(p), p.getParent() == null ? p : p.getParent()));
				return;
			}
			ctx.header("Content-Disposition", "attachment; filename=\"" + p.getFileName().toString().replace("\"", "") + "\"");
			ctx.header("Content-Length", String.valueOf(Files.size(p)));
			ctx.contentType("application/octet-stream");
			ctx.result(Files.newInputStream(p));
		}));

		routes.post("/api/files/upload", action((ctx, me) -> {
			Path dir = path(ctx, me, ctx.queryParam("path"), true);
			if (!Files.isDirectory(dir)) throw new IOException("Upload into a folder.");
			boolean overwrite = "1".equals(ctx.queryParam("overwrite"));
			List<String> saved = new ArrayList<>();
			for (UploadedFile upload : ctx.uploadedFiles("files")) {
				try (InputStream in = upload.content()) {
					Path target = files.saveUpload(dir, upload.filename(), in, overwrite);
					saved.add(files.rel(target));
				}
			}
			audit.log(me.name(), "files.upload", String.join(", ", saved), clientIp(ctx));
			json(ctx, Map.of("ok", true, "saved", saved));
		}));

		routes.post("/api/files/mkdir", action((ctx, me) -> {
			Path dir = path(ctx, me, str(body(ctx), "path"), true);
			files.mkdir(dir);
			audit.log(me.name(), "files.mkdir", files.rel(dir), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/files/move", action((ctx, me) -> {
			JsonObject body = body(ctx);
			Path from = path(ctx, me, str(body, "from"), true);
			Path to = path(ctx, me, str(body, "to"), true);
			files.move(from, to);
			audit.log(me.name(), "files.move", files.rel(from) + " → " + files.rel(to), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/files/copy", action((ctx, me) -> {
			JsonObject body = body(ctx);
			Path from = path(ctx, me, str(body, "from"), false);
			Path to = path(ctx, me, str(body, "to"), true);
			files.copy(from, to);
			audit.log(me.name(), "files.copy", files.rel(from) + " → " + files.rel(to), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/files/delete", action((ctx, me) -> {
			List<String> paths = strings(body(ctx), "paths");
			if (paths == null || paths.isEmpty()) throw new IOException("Nothing chosen.");
			List<Path> resolved = new ArrayList<>();
			for (String rel : paths) resolved.add(path(ctx, me, rel, true));
			for (Path p : resolved) files.trash(p);
			audit.log(me.name(), "files.delete", String.join(", ", paths) + " (to .panel-trash, kept 7 days)", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/files/unzip", action((ctx, me) -> {
			Path zip = path(ctx, me, str(body(ctx), "path"), true);
			int count = files.unzip(zip, zip.getParent());
			audit.log(me.name(), "files.unzip", files.rel(zip) + " (" + count + " files)", clientIp(ctx));
			json(ctx, Map.of("ok", true, "files", count));
		}));

		routes.post("/api/files/zip", action((ctx, me) -> {
			JsonObject body = body(ctx);
			List<String> paths = strings(body, "paths");
			if (paths == null || paths.isEmpty()) throw new IOException("Nothing chosen.");
			List<Path> resolved = new ArrayList<>();
			for (String rel : paths) resolved.add(path(ctx, me, rel, false));
			Path target = path(ctx, me, str(body, "to"), true);
			if (!target.getFileName().toString().endsWith(".zip")) target = target.resolveSibling(target.getFileName() + ".zip");
			files.checkWritable(target);
			if (Files.exists(target)) throw new IOException(files.rel(target) + " already exists.");
			try (OutputStream out = Files.newOutputStream(target)) {
				files.zip(resolved, target.getParent(), out, target);
			}
			audit.log(me.name(), "files.zip", String.join(", ", paths) + " → " + files.rel(target), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		// ── Config editor: every file in config/ with its format ──
		routes.get("/api/configs", action((ctx, me) -> {
			Path config = path(ctx, me, "config", false);
			List<Map<String, Object>> out = new ArrayList<>();
			if (Files.isDirectory(config)) {
				Files.walkFileTree(config, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						String name = file.getFileName().toString();
						if (!attrs.isRegularFile() || name.endsWith(".bak") || name.startsWith(".")) return FileVisitResult.CONTINUE;
						Map<String, Object> m = new HashMap<>();
						m.put("path", files.rel(file));
						m.put("size", attrs.size());
						m.put("time", attrs.lastModifiedTime().toMillis());
						m.put("format", ConfigCheck.format(name));
						out.add(m);
						return FileVisitResult.CONTINUE;
					}
				});
			}
			out.sort((a, b) -> ((String) a.get("path")).compareToIgnoreCase((String) b.get("path")));
			json(ctx, Map.of("files", out));
		}));

		// ── Log cleanup ──
		routes.get("/api/cleanup", guard(Permissions.FILES_READ, (ctx, me) -> json(ctx, cleanup.view())));
		routes.post("/api/cleanup/settings", guard(Permissions.FILES_WRITE, (ctx, me) -> {
			LogCleanup.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), LogCleanup.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unreadable settings");
				return;
			}
			String problem = cleanup.save(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			audit.log(me.name(), "files.cleanup.settings", next.rules.stream().map(r -> r.folder + "/" + r.pattern + " > " + r.maxAgeDays + " d"
					+ (r.enabled ? "" : " (off)")).reduce((a, b) -> a + ", " + b).orElse("no rules") + (next.daily ? ", daily" : ", manual only"), clientIp(ctx));
			json(ctx, cleanup.view());
		}));
		routes.post("/api/cleanup/run", guard(Permissions.FILES_WRITE, (ctx, me) -> {
			List<LogCleanup.Candidate> deleted = cleanup.run();
			audit.log(me.name(), "files.cleanup", cleanup.lastResult(), clientIp(ctx));
			json(ctx, Map.of("ok", true, "deleted", deleted.size(), "result", cleanup.lastResult()));
		}));
	}

	/** Streams a zip without a temporary file. */
	private InputStream zipStream(List<Path> paths, Path base) throws IOException {
		PipedInputStream in = new PipedInputStream(1 << 16);
		PipedOutputStream out = new PipedOutputStream(in);
		Thread writer = new Thread(() -> {
			try (out) {
				files.zip(paths, base, out);
			} catch (IOException e) {
				// The download was cancelled.
			}
		}, "zip-download");
		writer.setDaemon(true);
		writer.start();
		return in;
	}

	/** "+3 −1 lines" without a full diff: lines only in the new or only in the old version. */
	static String changeSummary(String before, String after) {
		Map<String, Integer> counts = new HashMap<>();
		for (String l : (before == null ? "" : before).split("\n", -1)) counts.merge(l, 1, Integer::sum);
		int added = 0;
		for (String l : after.split("\n", -1)) {
			Integer c = counts.get(l);
			if (c == null || c == 0) added++;
			else counts.put(l, c - 1);
		}
		int removed = counts.values().stream().mapToInt(Integer::intValue).sum();
		return "+" + added + " −" + removed + " lines";
	}
}
