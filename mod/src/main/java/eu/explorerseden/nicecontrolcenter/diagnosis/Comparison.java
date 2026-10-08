package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.data.History;

/**
 * Before/after comparison of two views: two reports, or a report and the live last 15 minutes.
 * Reports are read back from the data embedded in their HTML, so older reports work too.
 */
public final class Comparison {
	public static final String LIVE = "live";
	private static final String MARKER = "window.NP_REPORT = ";
	private static final int ROWS = 15;

	public record Delta(String key, String label, double a, double b, double change) {
	}

	public record Headline(String label, double a, double b, String unit, boolean lowerIsBetter) {
	}

	public record Result(String a, String b, String aLabel, String bLabel, List<Headline> headline, Map<String, List<Delta>> sections) {
	}

	private Comparison() {
	}

	public static Result compare(String a, String b, Path reportsDir) throws IOException {
		JsonObject viewA = load(a, reportsDir);
		JsonObject viewB = load(b, reportsDir);
		List<Headline> headline = new ArrayList<>();
		JsonObject kpiA = viewA.getAsJsonObject("kpi");
		JsonObject kpiB = viewB.getAsJsonObject("kpi");
		headline.add(new Headline("TPS", num(kpiA, "tps"), num(kpiB, "tps"), "", false));
		headline.add(new Headline("MSPT median", median(kpiA), median(kpiB), "ms", true));
		headline.add(new Headline("MSPT 95%ile", num(kpiA, "msptP95"), num(kpiB, "msptP95"), "ms", true));
		headline.add(new Headline("MSPT max", num(kpiA, "msptMax"), num(kpiB, "msptMax"), "ms", true));
		headline.add(new Headline("Entities", num(kpiA.getAsJsonObject("counts"), "entities"), num(kpiB.getAsJsonObject("counts"), "entities"), "", true));
		headline.add(new Headline("Loaded chunks", num(kpiA.getAsJsonObject("counts"), "chunks"), num(kpiB.getAsJsonObject("counts"), "chunks"), "", true));

		Map<String, List<Delta>> sections = new LinkedHashMap<>();
		sections.put("Where the tick goes", diff(rows(viewA, "phases", "phase", "label"), rows(viewB, "phases", "phase", "label")));
		sections.put("Data packs", diff(rows(viewA, "packs", "id", "name"), rows(viewB, "packs", "id", "name")));
		sections.put("Functions", diff(functions(viewA), functions(viewB)));
		sections.put("Mods", diff(rows(viewA, "mods", "id", "name"), rows(viewB, "mods", "id", "name")));
		sections.put("Entities", diff(rows(viewA, "entities", "id", "id"), rows(viewB, "entities", "id", "id")));
		sections.put("Block entities", diff(rows(viewA, "blockEntities", "id", "id"), rows(viewB, "blockEntities", "id", "id")));
		return new Result(a, b, label(a), label(b), headline, sections);
	}

	private static String label(String source) {
		return source.equals(LIVE) ? "Live (last 15 min)" : source.replace("nice-control-center-", "").replace(".html", "");
	}

	private static JsonObject load(String source, Path reportsDir) throws IOException {
		if (source.equals(LIVE)) {
			return Json.GSON.toJsonTree(Views.full(History.window(15))).getAsJsonObject();
		}
		if (!source.matches("[A-Za-z0-9._-]+\\.html")) {
			throw new IOException("Bad report name");
		}
		String html = Files.readString(reportsDir.resolve(source), StandardCharsets.UTF_8);
		int start = html.indexOf(MARKER);
		int end = start < 0 ? -1 : html.indexOf(";</script>", start);
		if (start < 0 || end < 0) {
			throw new IOException("No Nice Control Center data in " + source);
		}
		String json = html.substring(start + MARKER.length(), end).replace("<\\/", "</");
		return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("view");
	}

	private record Row(String label, double ms) {
	}

	private static Map<String, Row> rows(JsonObject view, String array, String keyField, String labelField) {
		Map<String, Row> rows = new LinkedHashMap<>();
		if (!view.has(array)) {
			return rows;
		}
		for (JsonElement element : view.getAsJsonArray(array)) {
			JsonObject o = element.getAsJsonObject();
			String key = str(o, keyField);
			rows.put(key, new Row(prettyLabel(str(o, labelField)), num(o, "ms")));
		}
		return rows;
	}

	private static Map<String, Row> functions(JsonObject view) {
		Map<String, Row> rows = new LinkedHashMap<>();
		if (!view.has("packs")) {
			return rows;
		}
		for (JsonElement pack : view.getAsJsonArray("packs")) {
			for (JsonElement fn : pack.getAsJsonObject().getAsJsonArray("functions")) {
				JsonObject o = fn.getAsJsonObject();
				rows.put(str(o, "id"), new Row(str(o, "id"), num(o, "ms")));
			}
		}
		return rows;
	}

	private static List<Delta> diff(Map<String, Row> a, Map<String, Row> b) {
		Map<String, Delta> deltas = new LinkedHashMap<>();
		a.forEach((key, row) -> {
			Row other = b.get(key);
			double bMs = other == null ? 0 : other.ms();
			deltas.put(key, new Delta(key, row.label(), row.ms(), bMs, bMs - row.ms()));
		});
		b.forEach((key, row) -> deltas.putIfAbsent(key, new Delta(key, row.label(), 0, row.ms(), row.ms())));
		List<Delta> result = new ArrayList<>(deltas.values());
		result.removeIf(d -> Math.abs(d.change()) < 0.005 && d.a() < 0.005);
		result.sort(Comparator.comparingDouble((Delta d) -> Math.abs(d.change())).reversed());
		return result.size() > ROWS ? new ArrayList<>(result.subList(0, ROWS)) : result;
	}

	private static String prettyLabel(String label) {
		return label.contains(":") && !label.contains(" ") ? Diagnoser.prettyType(label) : label;
	}

	private static String str(JsonObject o, String field) {
		JsonElement e = o.get(field);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	/** Reports from before the min/med/95%/max change call the median "msptP50". */
	private static double median(JsonObject kpi) {
		return kpi.has("msptMedian") ? num(kpi, "msptMedian") : num(kpi, "msptP50");
	}

	private static double num(JsonObject o, String field) {
		if (o == null) {
			return 0;
		}
		JsonElement e = o.get(field);
		return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
	}
}
