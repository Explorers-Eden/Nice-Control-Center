package eu.explorerseden.nicecontrolcenter.record;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParseException;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.Point;
import eu.explorerseden.nicecontrolcenter.diagnosis.Views;

/** Builds a single offline HTML file from a recording: the dashboard page with the data and assets inlined. */
public final class ReportWriter {
	private static final int MAX_POINTS = 3600;
	private static final String WEB = "/assets/nicecontrolcenter/web/";

	public record Meta(String server, String version, String startedBy, long start, long end, String reason) {
	}

	private ReportWriter() {
	}

	public static void write(Path recording, Path report, Meta meta) throws IOException {
		List<Point> points = new ArrayList<>();
		Breakdown total = new Breakdown();
		try (BufferedReader reader = Files.newBufferedReader(recording)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				try {
					Recorder.Line entry = Json.GSON.fromJson(line, Recorder.Line.class);
					if (entry.p() != null) {
						points.add(entry.p());
					}
					if (entry.m() != null) {
						total.merge(entry.m());
					}
				} catch (JsonParseException e) {
					// Half-written last line after a crash; skip it.
				}
			}
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("meta", meta);
		data.put("points", downsample(points));
		data.put("view", Views.full(total));
		Files.writeString(report, render(Json.GSON.toJson(data)), StandardCharsets.UTF_8);
	}

	/** Averages neighbouring seconds so long recordings stay small, keeping the worst tick of each group. */
	private static List<Point> downsample(List<Point> points) {
		if (points.size() <= MAX_POINTS) {
			return points;
		}
		int group = (int) Math.ceil(points.size() / (double) MAX_POINTS);
		List<Point> result = new ArrayList<>();
		for (int i = 0; i < points.size(); i += group) {
			List<Point> part = points.subList(i, Math.min(points.size(), i + group));
			int n = part.size();
			double tps = 0, mspt = 0, msptMax = 0, cpuP = 0, cpuS = 0;
			boolean paused = true;
			long heap = 0, heapMax = 0, heapLive = 0, gc = 0;
			int entities = 0, blockEntities = 0, chunks = 0, players = 0;
			for (Point p : part) {
				paused &= p.paused();
				tps += p.tps();
				mspt += p.mspt();
				msptMax = Math.max(msptMax, p.msptMax());
				cpuP += p.cpuProcess();
				cpuS += p.cpuSystem();
				heap += p.heapUsed();
				heapMax = Math.max(heapMax, p.heapMax());
				heapLive = Math.max(heapLive, p.heapLive());
				gc += p.gcMs();
				entities = Math.max(entities, p.entities());
				blockEntities = Math.max(blockEntities, p.blockEntities());
				chunks = Math.max(chunks, p.chunks());
				players = Math.max(players, p.players());
			}
			result.add(new Point(part.get(0).t(), tps / n, mspt / n, msptMax, heap / n, heapMax, heapLive, cpuP / n, cpuS / n,
					entities, blockEntities, chunks, players, gc, paused));
		}
		return result;
	}

	private static String render(String json) throws IOException {
		String html = resource("index.html");
		String css = resource("app.css");
		String js = resource("app.js");
		Map<String, String> images = Map.of("img/favicon.ico", dataUri("img/favicon.ico", "image/x-icon"));
		for (Map.Entry<String, String> image : images.entrySet()) {
			html = html.replace(image.getKey(), image.getValue());
			css = css.replace(image.getKey(), image.getValue());
		}
		String safeJson = json.replace("</", "<\\/");
		html = html.replace("<link rel=\"stylesheet\" href=\"app.css\">", "<style>\n" + css + "\n</style>");
		html = html.replace("<script src=\"app.js\"></script>",
				"<script>window.NP_REPORT = " + safeJson + ";</script>\n<script>\n" + js + "\n</script>");
		return html;
	}

	private static String resource(String name) throws IOException {
		try (InputStream in = ReportWriter.class.getResourceAsStream(WEB + name)) {
			if (in == null) {
				throw new IOException("Missing resource " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String dataUri(String name, String type) throws IOException {
		try (InputStream in = ReportWriter.class.getResourceAsStream(WEB + name)) {
			if (in == null) {
				return name;
			}
			return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
		}
	}
}
