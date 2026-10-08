package eu.explorerseden.nicecontrolcenter.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

/**
 * Finds the newest server resource pack from a source and works out its SHA-1:
 * <ul>
 * <li>{@code https://…/pack.zip}: any direct link, checked with If-None-Match / If-Modified-Since</li>
 * <li>{@code github:owner/repo[@tag][#name]}: the .zip asset of the latest (or tagged) release</li>
 * <li>{@code modrinth:<slug or id>}: the newest version for this Minecraft version</li>
 * </ul>
 * Background threads only.
 */
final class ResourcePack {
	/** Clients refuse packs larger than this. */
	private static final long MAX_BYTES = 250L * 1024 * 1024;

	/** What was found last time; lets unchanged sources skip the download. Saved with the update state. */
	static final class Cache {
		String source;
		String marker;
		String etag;
		String lastModified;
		String url;
		String sha1;
		String version;
		String changelog;
		String published;
		String projectUrl;
		String warning;
		long size;
	}

	private ResourcePack() {
	}

	/** Resolves the source; returns the cache, refreshed if the pack changed. */
	static Cache resolve(String source, List<String> channels, Cache cache, Path stagingDir) throws IOException {
		source = source.trim();
		if (cache == null || !source.equals(cache.source)) {
			cache = new Cache();
			cache.source = source;
		}
		if (source.startsWith("github:")) {
			return github(source.substring(7), cache, stagingDir);
		}
		if (source.startsWith("modrinth:")) {
			return modrinth(source.substring(9), channels, cache, stagingDir);
		}
		if (source.startsWith("https://") || source.startsWith("http://")) {
			return url(source, cache, stagingDir);
		}
		throw new IOException("resource_pack_source must be a https:// link, github:owner/repo or modrinth:project");
	}

	private static Cache url(String url, Cache cache, Path stagingDir) throws IOException {
		Path temp = temp(stagingDir);
		Sources.Fetched fetched = Sources.downloadIfChanged(url, url.equals(cache.url) ? cache.etag : null,
				url.equals(cache.url) ? cache.lastModified : null, temp);
		if (fetched.status() == 304 && cache.sha1 != null) {
			return cache;
		}
		try {
			inspect(temp, cache);
		} finally {
			Files.deleteIfExists(temp);
		}
		cache.url = url;
		cache.etag = fetched.etag();
		cache.lastModified = fetched.lastModified();
		cache.version = fetched.lastModified().isEmpty() ? cache.sha1.substring(0, 8) : fetched.lastModified();
		cache.projectUrl = null;
		return cache;
	}

	private static Cache github(String spec, Cache cache, Path stagingDir) throws IOException {
		String name = null;
		String tag = null;
		int hash = spec.indexOf('#');
		if (hash >= 0) {
			name = spec.substring(hash + 1);
			spec = spec.substring(0, hash);
		}
		int at = spec.indexOf('@');
		if (at >= 0) {
			tag = spec.substring(at + 1);
			spec = spec.substring(0, at);
		}
		if (!spec.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
			throw new IOException("github: needs owner/repo, e.g. github:Explorers-Eden/Explorers-Eden-Resources");
		}
		JsonObject release = Sources.githubRelease(spec, tag);
		if (release == null) {
			throw new IOException("No release found in " + spec + (tag != null ? " with tag " + tag : ""));
		}
		JsonObject asset = null;
		for (JsonElement element : release.getAsJsonArray("assets")) {
			JsonObject candidate = element.getAsJsonObject();
			String file = str(candidate, "name");
			if (file.endsWith(".zip") && (name == null || file.contains(name)) && asset == null) {
				asset = candidate;
			}
		}
		if (asset == null) {
			throw new IOException("The release has no .zip file" + (name != null ? " matching " + name : ""));
		}
		String marker = asset.get("id").getAsString() + "|" + str(asset, "updated_at") + "|" + str(asset, "digest");
		if (marker.equals(cache.marker) && cache.sha1 != null) {
			return cache;
		}
		String url = str(asset, "browser_download_url");
		download(url, cache, stagingDir);
		cache.marker = marker;
		cache.url = url;
		cache.version = str(release, "tag_name") + " · " + str(asset, "updated_at").replace('T', ' ').replace("Z", "");
		cache.changelog = str(release, "body");
		cache.published = str(asset, "updated_at");
		cache.projectUrl = "https://github.com/" + spec;
		return cache;
	}

	private static Cache modrinth(String project, List<String> channels, Cache cache, Path stagingDir) throws IOException {
		String gameVersion = SharedConstants.getCurrentVersion().name();
		JsonObject chosen = null;
		for (JsonElement element : Sources.modrinthVersions(project, "minecraft", gameVersion)) {
			JsonObject version = element.getAsJsonObject();
			if (channels.contains(str(version, "version_type"))) {
				chosen = version;
				break;
			}
		}
		if (chosen == null) {
			throw new IOException("No " + String.join("/", channels) + " version of " + project + " for Minecraft " + gameVersion + " on Modrinth");
		}
		JsonObject file = null;
		for (JsonElement element : chosen.getAsJsonArray("files")) {
			JsonObject candidate = element.getAsJsonObject();
			if (file == null || (candidate.has("primary") && candidate.get("primary").getAsBoolean())) {
				file = candidate;
			}
		}
		if (file == null) {
			throw new IOException("The newest version has no file");
		}
		String marker = chosen.get("id").getAsString();
		if (marker.equals(cache.marker) && cache.sha1 != null) {
			return cache;
		}
		String url = str(file, "url");
		download(url, cache, stagingDir);
		String expected = file.getAsJsonObject("hashes").get("sha1").getAsString();
		if (!expected.equalsIgnoreCase(cache.sha1)) {
			throw new IOException("checksum doesn't match Modrinth's");
		}
		cache.marker = marker;
		cache.url = url;
		cache.version = str(chosen, "version_number");
		cache.changelog = str(chosen, "changelog");
		cache.published = str(chosen, "date_published");
		cache.projectUrl = "https://modrinth.com/resourcepack/" + project;
		return cache;
	}

	private static void download(String url, Cache cache, Path stagingDir) throws IOException {
		Path temp = temp(stagingDir);
		try {
			Sources.download(url, temp);
			inspect(temp, cache);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** Hashes and checks a downloaded zip; fills sha1, size and warning. */
	private static void inspect(Path file, Cache cache) throws IOException {
		long size = Files.size(file);
		if (size > MAX_BYTES) {
			throw new IOException("the pack is " + (size >> 20) + " MB; clients only accept up to 250 MB");
		}
		String[] hashes = Installed.hashes(file);
		if (hashes == null) {
			throw new IOException("could not read the download");
		}
		String warning = null;
		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry meta = zip.getEntry("pack.mcmeta");
			if (meta == null) {
				throw new IOException("not a resource pack (no pack.mcmeta in the zip)");
			}
			try (InputStream in = zip.getInputStream(meta)) {
				warning = formatWarning(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (RuntimeException e) {
				warning = "pack.mcmeta couldn't be read";
			}
		} catch (java.util.zip.ZipException e) {
			throw new IOException("not a zip file");
		}
		cache.sha1 = hashes[0];
		cache.size = size;
		cache.warning = warning;
	}

	/** A note if the pack is made for another Minecraft version, else null. */
	private static String formatWarning(JsonObject mcmeta) {
		JsonObject pack = mcmeta.getAsJsonObject("pack");
		if (pack == null) {
			return "pack.mcmeta has no \"pack\" section";
		}
		int server = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
		Integer min = major(pack.get("min_format"));
		Integer max = major(pack.get("max_format"));
		Integer format = major(pack.get("pack_format"));
		if (min == null) {
			min = format;
		}
		if (max == null) {
			max = format;
		}
		if (min == null && max == null) {
			return null;
		}
		if ((min != null && min > server) || (max != null && max < server)) {
			return "made for pack format " + (min != null && max != null && !min.equals(max) ? min + "–" + max : (min != null ? min : max))
					+ ", this server uses " + server + "; players will see an \"incompatible\" note";
		}
		return null;
	}

	private static Integer major(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonArray()) {
			return element.getAsJsonArray().isEmpty() ? null : element.getAsJsonArray().get(0).getAsInt();
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Path temp(Path stagingDir) throws IOException {
		Files.createDirectories(stagingDir);
		return Files.createTempFile(stagingDir, "resourcepack", ".zip");
	}

	private static String str(JsonObject object, String key) {
		JsonElement e = object.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}
}
