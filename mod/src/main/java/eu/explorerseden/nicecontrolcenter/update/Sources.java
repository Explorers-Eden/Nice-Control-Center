package eu.explorerseden.nicecontrolcenter.update;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/** Talks to Modrinth and GitHub. Background threads only. */
final class Sources {
	private static final String MODRINTH = "https://api.modrinth.com/v2";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private Sources() {
	}

	static String userAgent() {
		String version = FabricLoader.getInstance().getModContainer("nicecontrolcenter")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		return "NiceRon/nice-control-center/" + version + " (explorerseden.eu)";
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", userAgent());
	}

	private static JsonElement send(HttpRequest request) throws IOException {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() / 100 != 2) {
				throw new IOException("HTTP " + response.statusCode() + " from " + request.uri().getHost());
			}
			return JsonParser.parseString(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static JsonObject postJson(String url, JsonObject body) throws IOException {
		JsonElement result = send(request(url).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
		return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
	}

	private static JsonArray array(Collection<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	/** Modrinth versions the given files belong to, keyed by SHA-1. */
	static Map<String, JsonObject> modrinthCurrent(Collection<String> sha1s) throws IOException {
		JsonObject body = new JsonObject();
		body.add("hashes", array(sha1s));
		body.addProperty("algorithm", "sha1");
		return objects(postJson(MODRINTH + "/version_files", body));
	}

	/** Newest compatible Modrinth version for each file, keyed by SHA-1. */
	static Map<String, JsonObject> modrinthLatest(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
		JsonObject body = new JsonObject();
		body.add("hashes", array(sha1s));
		body.addProperty("algorithm", "sha1");
		body.add("loaders", array(java.util.List.of(loader)));
		body.add("game_versions", array(java.util.List.of(gameVersion)));
		return objects(postJson(MODRINTH + "/version_files/update", body));
	}

	/** All versions of a project for a loader and game version, newest first. */
	static JsonArray modrinthVersions(String projectId, String loader, String gameVersion) throws IOException {
		String url = MODRINTH + "/project/" + projectId + "/version?loaders=" + encode("[\"" + loader + "\"]")
				+ "&game_versions=" + encode("[\"" + gameVersion + "\"]");
		JsonElement result = send(request(url).GET().build());
		return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
	}

	/** Project titles and slugs, keyed by project id. */
	static Map<String, JsonObject> modrinthProjects(Collection<String> ids) throws IOException {
		Map<String, JsonObject> result = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return result;
		}
		JsonElement response = send(request(MODRINTH + "/projects?ids=" + encode(array(ids).toString())).GET().build());
		if (response != null && response.isJsonArray()) {
			for (JsonElement element : response.getAsJsonArray()) {
				JsonObject project = element.getAsJsonObject();
				result.put(project.get("id").getAsString(), project);
			}
		}
		return result;
	}

	/** Latest GitHub release of "owner/repo", or null. */
	static JsonObject githubLatest(String repo) throws IOException {
		JsonElement result = send(request("https://api.github.com/repos/" + repo + "/releases/latest")
				.header("Accept", "application/vnd.github+json").GET().build());
		return result != null && result.isJsonObject() ? result.getAsJsonObject() : null;
	}

	/** GitHub release of "owner/repo" with the given tag (or the latest if null), or null. */
	static JsonObject githubRelease(String repo, String tag) throws IOException {
		if (tag == null || tag.isEmpty()) {
			return githubLatest(repo);
		}
		JsonElement result = send(request("https://api.github.com/repos/" + repo + "/releases/tags/" + encode(tag))
				.header("Accept", "application/vnd.github+json").GET().build());
		return result != null && result.isJsonObject() ? result.getAsJsonObject() : null;
	}

	/** Result of a conditional download: 304 means unchanged and nothing was written. */
	record Fetched(int status, String etag, String lastModified) {
	}

	/** GET with If-None-Match / If-Modified-Since; writes the body to target unless it's unchanged. */
	static Fetched downloadIfChanged(String url, String etag, String lastModified, Path target) throws IOException {
		HttpRequest.Builder builder = request(url).timeout(Duration.ofMinutes(5)).GET();
		if (etag != null && !etag.isEmpty()) {
			builder.header("If-None-Match", etag);
		}
		if (lastModified != null && !lastModified.isEmpty()) {
			builder.header("If-Modified-Since", lastModified);
		}
		try {
			HttpResponse<Path> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofFile(target));
			if (response.statusCode() == 304) {
				Files.deleteIfExists(target);
				return new Fetched(304, etag, lastModified);
			}
			if (response.statusCode() / 100 != 2) {
				Files.deleteIfExists(target);
				throw new IOException("HTTP " + response.statusCode() + " while downloading");
			}
			return new Fetched(response.statusCode(), response.headers().firstValue("ETag").orElse(""),
					response.headers().firstValue("Last-Modified").orElse(""));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	static void download(String url, Path target) throws IOException {
		try {
			HttpResponse<Path> response = HTTP.send(request(url).timeout(Duration.ofMinutes(5)).GET().build(),
					HttpResponse.BodyHandlers.ofFile(target));
			if (response.statusCode() / 100 != 2) {
				Files.deleteIfExists(target);
				throw new IOException("HTTP " + response.statusCode() + " while downloading");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static Map<String, JsonObject> objects(JsonObject object) {
		Map<String, JsonObject> result = new LinkedHashMap<>();
		object.entrySet().forEach(e -> {
			if (e.getValue().isJsonObject()) {
				result.put(e.getKey(), e.getValue().getAsJsonObject());
			}
		});
		return result;
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}
}
