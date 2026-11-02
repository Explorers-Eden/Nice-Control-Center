package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;

/**
 * What a settings page deleted, sent along with the save ("removed": {"arenas": ["id"…], "points": ["key"…]}).
 * Things made in game while the page was open aren't on it; only what's named here goes, the rest of the file
 * stays (a page opened before /game create or entrance add mustn't erase them).
 */
final class Removed {
	private final JsonObject removed;

	private Removed(JsonObject removed) {
		this.removed = removed;
	}

	static Removed from(String body) {
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			return new Removed(root.has("removed") && root.get("removed").isJsonObject() ? root.getAsJsonObject("removed") : new JsonObject());
		} catch (RuntimeException e) {
			return new Removed(new JsonObject());
		}
	}

	/** The keys deleted of that kind. */
	Set<String> of(String kind) {
		Set<String> out = new HashSet<>();
		if (removed.has(kind) && removed.get(kind).isJsonArray()) {
			for (JsonElement e : removed.getAsJsonArray(kind)) if (e.isJsonPrimitive()) out.add(e.getAsString());
		}
		return out;
	}

	/** A spot's key, the same as the page makes it: "owner|field|dim|x2|y2|z2" (halves rounded). */
	static String spot(String owner, String field, eu.explorerseden.nicecontrolcenter.Games.Pos p) {
		return owner + "|" + field + "|" + p.dim + "|" + Math.round(p.x * 2) + "|" + Math.round(p.y * 2) + "|" + Math.round(p.z * 2);
	}
}
