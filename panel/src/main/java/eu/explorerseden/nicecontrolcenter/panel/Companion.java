package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Keeps the Nice Control Center mod in mods/ and up to date before each start: the newest GitHub
 * release built for the server's Minecraft version. The mod gives the panel its dashboard.
 */
final class Companion {
	private static final Pattern TAG = Pattern.compile("mod-v([0-9][0-9A-Za-z.+-]*)-mc(.+)");
	private static final Pattern INSTALLED = Pattern.compile("nice-control-center-([0-9][0-9.]*)(?:-mc[^/]*)?\\.jar");
	private static final Pattern LAUNCHER_NAME = Pattern.compile("fabric-server-mc\\.([0-9][^-]*)-loader");

	private final String repo = Panel.env("COMPANION_REPO", "Explorers-Eden/Nice-Control-Center");
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

	record Release(String version, String mc, String url, String file) {
	}

	/** Never throws: a failed check only leaves the installed version as it is. */
	void ensure(Path serverDir, String serverJar, Consumer<String> log) {
		String mc = minecraftVersion(serverDir.resolve(serverJar));
		if (mc == null) {
			log.accept("Couldn't tell the Minecraft version from " + serverJar + ", so the Nice Control Center mod isn't checked");
			return;
		}
		Path mods = serverDir.resolve("mods");
		List<Path> installed = installed(mods);
		String current = installed.isEmpty() ? null : version(installed.getFirst());
		Release latest;
		try {
			latest = latest(mc);
		} catch (IOException | InterruptedException | RuntimeException e) {
			log.accept("Couldn't check for the Nice Control Center mod (" + e.getMessage() + ")" + (current == null ? "; the dashboard stays off" : ""));
			return;
		}
		if (latest == null) {
			if (current == null) log.accept("There's no Nice Control Center mod release for Minecraft " + mc + " yet; the dashboard stays off");
			return;
		}
		if (current != null && compare(current, latest.version()) >= 0) return;
		try {
			Files.createDirectories(mods);
			Path tmp = mods.resolve(".nice-control-center.download");
			HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(latest.url())).timeout(Duration.ofMinutes(2)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
			try (InputStream in = response.body()) {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			}
			for (Path old : installed) Files.deleteIfExists(old);
			Files.move(tmp, mods.resolve(latest.file()), StandardCopyOption.REPLACE_EXISTING);
			log.accept(current == null ? "Installed the Nice Control Center mod " + latest.version() + " for Minecraft " + mc
					: "Updated the Nice Control Center mod from " + current + " to " + latest.version());
		} catch (IOException | InterruptedException e) {
			log.accept("Couldn't download the Nice Control Center mod: " + e.getMessage());
		}
	}

	/** Fabric's server launcher carries install.properties with game-version; the file name is the fallback. */
	static String minecraftVersion(Path launcher) {
		if (Files.isRegularFile(launcher)) {
			try (ZipFile zip = new ZipFile(launcher.toFile())) {
				var entry = zip.getEntry("install.properties");
				if (entry != null) {
					Properties props = new Properties();
					try (InputStream in = zip.getInputStream(entry)) {
						props.load(in);
					}
					String version = props.getProperty("game-version");
					if (version != null && !version.isBlank()) return version.strip();
				}
			} catch (IOException e) {
				// Not a zip: try the name.
			}
		}
		Matcher name = LAUNCHER_NAME.matcher(launcher.getFileName().toString());
		return name.find() ? name.group(1) : null;
	}

	Release latest(String mc) throws IOException, InterruptedException {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=100"))
				.timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("GitHub answered " + response.statusCode());
		Release best = null;
		JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
		for (JsonElement element : releases) {
			JsonObject release = element.getAsJsonObject();
			if (release.get("draft").getAsBoolean()) continue;
			Matcher tag = TAG.matcher(release.get("tag_name").getAsString());
			// A release for "26.1" also serves 26.1.1 and 26.1.2; an exact match wins.
			if (!tag.matches() || !(tag.group(2).equals(mc) || mc.startsWith(tag.group(2) + "."))) continue;
			if (best != null && best.mc().equals(mc) && !tag.group(2).equals(mc)) continue;
			for (JsonElement a : release.getAsJsonArray("assets")) {
				JsonObject asset = a.getAsJsonObject();
				String name = asset.get("name").getAsString();
				if (!name.endsWith(".jar")) continue;
				boolean exact = tag.group(2).equals(mc);
				boolean bestExact = best != null && best.mc().equals(mc);
				if (best == null || (exact && !bestExact) || (exact == bestExact && compare(tag.group(1), best.version()) > 0)) {
					best = new Release(tag.group(1), exact ? mc : tag.group(2), asset.get("browser_download_url").getAsString(), name);
				}
			}
		}
		return best;
	}

	private static List<Path> installed(Path mods) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(mods)) return out;
		try (Stream<Path> files = Files.list(mods)) {
			files.filter(f -> INSTALLED.matcher(f.getFileName().toString()).matches())
					.sorted((a, b) -> compare(version(b), version(a))).forEach(out::add);
		} catch (IOException e) {
			// Treated as not installed.
		}
		return out;
	}

	private static String version(Path jar) {
		Matcher m = INSTALLED.matcher(jar.getFileName().toString());
		return m.matches() ? m.group(1) : "0";
	}

	/** Compares dotted version numbers: 1.0.10 > 1.0.9. */
	static int compare(String a, String b) {
		String[] x = a.split("[.+-]");
		String[] y = b.split("[.+-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? number(x[i]) : 0;
			int q = i < y.length ? number(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int number(String part) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
