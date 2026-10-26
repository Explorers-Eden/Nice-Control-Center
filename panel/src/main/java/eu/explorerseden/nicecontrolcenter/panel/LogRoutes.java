package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.LogNoise;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** The server's log files (logs/latest.log and the zipped ones of earlier days): list, read, filter, group. */
final class LogRoutes {
	/** "[14:03:22] [Server thread/WARN]: message" (vanilla's layout; a date in front is fine too). */
	private static final Pattern LINE = Pattern.compile("^\\[([^\\]]*?)(\\d{1,2}:\\d{2}:\\d{2})[^\\]]*\\] \\[([^\\]]*)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]: ?(.*)$");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+\\.log(\\.gz)?");
	private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_])-?\\d+(?:\\.\\d+)?");
	private static final int MAX_ENTRIES = 3000;

	private final Path logs;

	LogRoutes(Path serverDir) {
		this.logs = serverDir.resolve("logs");
	}

	record Entry(String time, String thread, String level, String message, List<String> more, boolean harmless) {
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/logs", guard(Permissions.CONSOLE_READ, (ctx, me) -> {
			List<Map<String, Object>> out = new ArrayList<>();
			if (Files.isDirectory(logs)) {
				try (var files = Files.list(logs)) {
					for (Path f : files.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).toList()) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("name", f.getFileName().toString());
						row.put("size", Files.size(f));
						row.put("modified", Files.getLastModifiedTime(f).toMillis());
						out.add(row);
					}
				}
			}
			out.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("modified")).reversed());
			json(ctx, Map.of("files", out.size() > 400 ? out.subList(0, 400) : out));
		}));

		routes.get("/api/logs/view", guard(Permissions.CONSOLE_READ, (ctx, me) -> {
			String name = ctx.queryParam("name");
			if (name == null || !NAME.matcher(name).matches() || !Files.isRegularFile(logs.resolve(name))) {
				error(ctx, HttpStatus.NOT_FOUND, "That log file isn't there.");
				return;
			}
			String level = String.valueOf(ctx.queryParam("level"));
			String q = ctx.queryParam("q") == null ? "" : ctx.queryParam("q").toLowerCase(Locale.ROOT).strip();
			boolean harmless = "1".equals(ctx.queryParam("harmless"));
			json(ctx, read(logs.resolve(name), level, q, harmless));
		}));
	}

	private Map<String, Object> read(Path file, String level, String q, boolean showHarmless) throws IOException {
		ArrayDeque<Entry> shown = new ArrayDeque<>();
		Map<String, Map<String, Object>> groups = new HashMap<>();
		int lines = 0;
		int warns = 0;
		int errors = 0;
		int matched = 0;
		Entry current = null;
		try (InputStream raw = Files.newInputStream(file);
				InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				lines++;
				Matcher m = LINE.matcher(line);
				if (!m.matches()) {
					// A stack trace or a message over several lines belongs to the entry above.
					if (current != null && current.more().size() < 60) current.more().add(line.length() > 600 ? line.substring(0, 600) + "…" : line);
					continue;
				}
				String message = m.group(5);
				String lvl = m.group(4);
				boolean harmless = (lvl.equals("WARN") || lvl.equals("ERROR")) && LogNoise.HARMLESS.matcher(message).find();
				Entry e = new Entry(m.group(2), m.group(3), lvl, message.length() > 2000 ? message.substring(0, 2000) + "…" : message, new ArrayList<>(), harmless);
				current = e;
				boolean problem = lvl.equals("WARN") || lvl.equals("ERROR") || lvl.equals("FATAL");
				if (problem) {
					if (lvl.equals("WARN")) warns++;
					else errors++;
					String key = lvl + " " + NUMBER.matcher(message.length() > 300 ? message.substring(0, 300) : message).replaceAll("#");
					Map<String, Object> g = groups.computeIfAbsent(key, k -> {
						Map<String, Object> n = new LinkedHashMap<>();
						n.put("level", lvl);
						n.put("example", e.message());
						n.put("first", e.time());
						n.put("count", 0);
						n.put("harmless", e.harmless());
						return n;
					});
					g.put("count", (Integer) g.get("count") + 1);
					g.put("last", e.time());
				}
				if (!wanted(e, level, q, showHarmless)) continue;
				matched++;
				shown.addLast(e);
				if (shown.size() > MAX_ENTRIES) shown.removeFirst();
			}
		}
		List<Map<String, Object>> grouped = new ArrayList<>(groups.values());
		grouped.removeIf(g -> !showHarmless && Boolean.TRUE.equals(g.get("harmless")));
		grouped.sort(Comparator.comparing((Map<String, Object> g) -> !g.get("level").equals("WARN") ? 0 : 1).thenComparing(g -> -(Integer) g.get("count")));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("lines", lines);
		out.put("warnings", warns);
		out.put("errors", errors);
		out.put("matched", matched);
		out.put("entries", shown);
		out.put("groups", grouped.size() > 100 ? grouped.subList(0, 100) : grouped);
		return out;
	}

	private static boolean wanted(Entry e, String level, String q, boolean showHarmless) {
		boolean problem = !e.level().equals("INFO") && !e.level().equals("DEBUG") && !e.level().equals("TRACE");
		if (e.harmless() && !showHarmless && !level.equals("all")) return false;
		switch (level) {
			case "errors" -> {
				if (!e.level().equals("ERROR") && !e.level().equals("FATAL")) return false;
			}
			case "warnings" -> {
				if (!problem) return false;
			}
			case "all" -> {
				if (e.level().equals("DEBUG") || e.level().equals("TRACE")) return false;
			}
			default -> {
			}
		}
		if (q.isEmpty()) return true;
		return e.message().toLowerCase(Locale.ROOT).contains(q) || e.thread().toLowerCase(Locale.ROOT).contains(q);
	}
}
