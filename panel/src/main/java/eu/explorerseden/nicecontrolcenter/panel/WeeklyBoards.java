package eu.explorerseden.nicecontrolcenter.panel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The weekly leaderboards as the mod keeps them (world/nicecontrolcenter-weekly.json), read for the panel's game
 * cards and the website: this week's and last week's boards, top 10 each, best first.
 */
final class WeeklyBoards {
	static final String FILE = "nicecontrolcenter-weekly.json";

	private WeeklyBoards() {
	}

	/** {week, lastWeek, boards: {key: board}, last: {key: board}}; board = {kind, name, unit, higher, top: [{name, best, tie}]}. */
	static Map<String, Object> read(Path world) {
		Map<String, Object> out = new LinkedHashMap<>();
		JsonObject root = null;
		try {
			Path f = world.resolve(FILE);
			if (Files.exists(f)) root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		} catch (Exception e) {
			root = null;
		}
		out.put("week", root != null && root.has("week") ? root.get("week").getAsString() : "");
		out.put("lastWeek", root != null && root.has("lastWeek") ? root.get("lastWeek").getAsString() : "");
		out.put("boards", boards(root == null ? null : root.getAsJsonObject("boards")));
		out.put("last", boards(root == null ? null : root.getAsJsonObject("last")));
		return out;
	}

	private static Map<String, Object> boards(JsonObject in) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (in == null) return out;
		for (Map.Entry<String, JsonElement> e : in.entrySet()) {
			if (!e.getValue().isJsonObject()) continue;
			JsonObject b = e.getValue().getAsJsonObject();
			boolean higher = !b.has("higher") || b.get("higher").getAsBoolean();
			List<Map<String, Object>> top = new ArrayList<>();
			JsonObject players = b.has("players") && b.get("players").isJsonObject() ? b.getAsJsonObject("players") : new JsonObject();
			for (Map.Entry<String, JsonElement> p : players.entrySet()) {
				JsonObject v = p.getValue().getAsJsonObject();
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("name", v.has("name") ? v.get("name").getAsString() : "?");
				row.put("best", v.has("best") ? v.get("best").getAsLong() : 0);
				row.put("tie", v.has("tie") ? v.get("tie").getAsLong() : 0);
				row.put("at", v.has("at") ? v.get("at").getAsLong() : 0);
				top.add(row);
			}
			top.sort((x, y) -> {
				long bx = (long) x.get("best"), by = (long) y.get("best");
				int c = higher ? Long.compare(by, bx) : Long.compare(bx, by);
				if (c == 0) c = Long.compare((long) x.get("tie"), (long) y.get("tie"));
				return c != 0 ? c : Long.compare((long) x.get("at"), (long) y.get("at"));
			});
			Map<String, Object> board = new LinkedHashMap<>();
			board.put("kind", b.has("kind") ? b.get("kind").getAsString() : "game");
			board.put("name", b.has("name") ? b.get("name").getAsString() : e.getKey());
			board.put("unit", b.has("unit") ? b.get("unit").getAsString() : "");
			board.put("higher", higher);
			board.put("top", top.size() > 10 ? top.subList(0, 10) : top);
			out.put(e.getKey(), board);
		}
		return out;
	}
}
