package eu.explorerseden.nicecontrolcenter.npcs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Skins from any PNG link: players' games only load skins from Mojang's own skin server, so the picture is uploaded
 * there through MineSkin (mineskin.org, free API key), which answers with the signed texture value. Off the server
 * thread.
 */
final class MineSkin {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String API = "https://api.mineskin.org";

	private MineSkin() {
	}

	/** The texture value for the picture at the link; fails with a message to show. */
	static CompletableFuture<String> upload(String link, String key) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("url", link);
				body.addProperty("visibility", "unlisted");
				JsonObject answer = send(HttpRequest.newBuilder(URI.create(API + "/v2/generate")).timeout(Duration.ofSeconds(60))
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())), key);
				String value = find(answer);
				// Queued instead of done: asked again until it is (about a minute at most).
				String job = answer.has("job") && answer.get("job").isJsonObject() && answer.getAsJsonObject("job").has("id")
						? answer.getAsJsonObject("job").get("id").getAsString() : null;
				for (int i = 0; value == null && job != null && i < 30; i++) {
					Thread.sleep(2000);
					answer = send(HttpRequest.newBuilder(URI.create(API + "/v2/queue/" + job)).timeout(Duration.ofSeconds(30)).GET(), key);
					value = find(answer);
					String status = answer.has("job") && answer.get("job").isJsonObject() && answer.getAsJsonObject("job").has("status")
							? answer.getAsJsonObject("job").get("status").getAsString() : "";
					if (status.equals("failed")) break;
				}
				if (value == null) throw new IllegalStateException(problem(answer));
				return value;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted");
			} catch (java.io.IOException e) {
				throw new IllegalStateException("MineSkin can't be reached: " + e.getMessage());
			}
		});
	}

	private static JsonObject send(HttpRequest.Builder b, String key) throws java.io.IOException, InterruptedException {
		b.header("User-Agent", "NiceControlCenter/1.0").header("Accept", "application/json");
		if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.strip());
		HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonElement e;
		try {
			e = JsonParser.parseString(r.body());
		} catch (RuntimeException ex) {
			throw new java.io.IOException("HTTP " + r.statusCode());
		}
		if (!e.isJsonObject()) throw new java.io.IOException("HTTP " + r.statusCode());
		JsonObject o = e.getAsJsonObject();
		if (r.statusCode() == 401 || r.statusCode() == 403) throw new IllegalStateException("MineSkin refused the API key (" + problem(o) + ")");
		return o;
	}

	/** The signed texture value anywhere in the answer (an object with "value" and "signature" holding a skin). */
	private static String find(JsonElement e) {
		if (e == null) return null;
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("value") && o.has("signature") && o.get("value").isJsonPrimitive()) {
				String v = o.get("value").getAsString();
				try {
					if (new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8).contains("textures")) return v;
				} catch (IllegalArgumentException ex) {
					// Not base64.
				}
			}
			for (var en : o.entrySet()) {
				String v = find(en.getValue());
				if (v != null) return v;
			}
		} else if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				String v = find(x);
				if (v != null) return v;
			}
		}
		return null;
	}

	private static String problem(JsonObject o) {
		for (String k : new String[] { "errors", "messages" }) {
			if (o.has(k) && o.get(k).isJsonArray() && !o.getAsJsonArray(k).isEmpty()) {
				JsonElement first = o.getAsJsonArray(k).get(0);
				if (first.isJsonObject() && first.getAsJsonObject().has("message")) return first.getAsJsonObject().get("message").getAsString();
				if (first.isJsonPrimitive()) return first.getAsString();
			}
		}
		if (o.has("error")) return o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : o.get("error").toString();
		return "no skin came back";
	}
}
