package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Minecraft and Fabric versions: update Fabric Loader within the Minecraft version, or move to another
 * Minecraft version. Mods are checked on Modrinth (by file hash) for the target version first. Every
 * update makes a backup, swaps launcher and mods, starts the server, and restores the backup if the
 * server doesn't come up.
 */
public final class Versions {
	public record Installed(String mc, String loader, String installer, String jar) {
	}

	/** One mod in mods/ and what an update to the target version would do with it. */
	public static final class ModCheck {
		public String file;
		public String id;
		public String name;
		public String version;
		/** ok, update, missing (on Modrinth, nothing for the target), unknown (not on Modrinth), companion */
		public String status;
		public String newVersion;
		public String newFile;
		transient String url;
		transient String sha1;
		transient String newSha1;
		/** Default action for the plan: keep, update or disable. */
		public String action;
		/** Fabric Loader this mod needs, from fabric.mod.json (e.g. ">=0.19.5"), when the target doesn't fit. */
		public String needsLoader;
		transient String loaderDep;
	}

	public record HistoryEntry(long time, String from, String to, String by, String backup, String result) {
	}

	private static final Pattern LAUNCHER = Pattern.compile("fabric-server-mc\\.(.+)-loader\\.(.+)-launcher\\.(.+)\\.jar");
	private static final String META = "https://meta.fabricmc.net/v2/versions";
	private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");
	private static final String MODRINTH = "https://api.modrinth.com/v2";

	private final Path serverDir;
	private final Supervisor server;
	private final Backups backups;
	private final Companion companion;
	private final Audit audit;
	private final Consumer<String> setServerJar;
	private final java.util.function.Supplier<String> serverJar;
	private final Path historyFile;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

	private volatile boolean running;
	private volatile String phase = "";
	private final List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile String result;

	public Versions(Path serverDir, Supervisor server, Backups backups, Companion companion, Audit audit,
			java.util.function.Supplier<String> serverJar, Consumer<String> setServerJar, Path historyFile) {
		this.serverDir = serverDir;
		this.server = server;
		this.backups = backups;
		this.companion = companion;
		this.audit = audit;
		this.serverJar = serverJar;
		this.setServerJar = setServerJar;
		this.historyFile = historyFile;
	}

	// ── What's installed, what's available ─────────────────────────────────

	public Installed installed() {
		String jar = serverJar.get();
		Path launcher = serverDir.resolve(jar);
		String mc = null;
		String loader = null;
		if (Files.isRegularFile(launcher)) {
			try (ZipFile zip = new ZipFile(launcher.toFile())) {
				var entry = zip.getEntry("install.properties");
				if (entry != null) {
					Properties props = new Properties();
					try (InputStream in = zip.getInputStream(entry)) {
						props.load(in);
					}
					mc = props.getProperty("game-version");
					loader = props.getProperty("fabric-loader-version");
				}
			} catch (IOException e) {
				// Use the name.
			}
		}
		var m = LAUNCHER.matcher(jar);
		String installer = m.matches() ? m.group(3) : null;
		if (mc == null && m.matches()) mc = m.group(1);
		if (loader == null && m.matches()) loader = m.group(2);
		return new Installed(mc, loader, installer, jar);
	}

	public Map<String, Object> available(String mc) throws IOException, InterruptedException {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> games = new ArrayList<>();
		for (JsonElement e : getJson(META + "/game").getAsJsonArray()) {
			JsonObject g = e.getAsJsonObject();
			games.add(Map.of("version", g.get("version").getAsString(), "stable", g.get("stable").getAsBoolean()));
		}
		out.put("games", games);
		if (mc != null) out.put("loaders", loaders(mc));
		return out;
	}

	private List<Map<String, Object>> loaders(String mc) throws IOException, InterruptedException {
		List<Map<String, Object>> out = new ArrayList<>();
		for (JsonElement e : getJson(META + "/loader/" + mc).getAsJsonArray()) {
			JsonObject l = e.getAsJsonObject().getAsJsonObject("loader");
			out.add(Map.of("version", l.get("version").getAsString(), "stable", l.get("stable").getAsBoolean()));
		}
		return out;
	}

	private String latestInstaller() throws IOException, InterruptedException {
		for (JsonElement e : getJson(META + "/installer").getAsJsonArray()) {
			if (e.getAsJsonObject().get("stable").getAsBoolean()) return e.getAsJsonObject().get("version").getAsString();
		}
		return getJson(META + "/installer").getAsJsonArray().get(0).getAsJsonObject().get("version").getAsString();
	}

	// ── Mod check ──────────────────────────────────────────────────────────

	/** Every mod in mods/ against the target Minecraft version. */
	public List<ModCheck> check(String targetMc) throws IOException, InterruptedException {
		return check(targetMc, null);
	}

	/** targetLoader (optional) also checks each mod's "depends": {"fabricloader": ">=x"}. */
	public List<ModCheck> check(String targetMc, String targetLoader) throws IOException, InterruptedException {
		String currentMc = installed().mc();
		boolean sameMc = targetMc.equals(currentMc);
		List<ModCheck> mods = new ArrayList<>();
		Path dir = serverDir.resolve("mods");
		if (!Files.isDirectory(dir)) return mods;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path jar : files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList()) {
				ModCheck m = new ModCheck();
				m.file = jar.getFileName().toString();
				m.sha1 = sha1(jar);
				readModJson(jar, m);
				mods.add(m);
			}
		}
		Map<String, ModCheck> byHash = new LinkedHashMap<>();
		for (ModCheck m : mods) byHash.put(m.sha1, m);
		JsonObject hashes = new JsonObject();
		JsonArray list = new JsonArray();
		byHash.keySet().forEach(list::add);
		hashes.add("hashes", list);
		hashes.addProperty("algorithm", "sha1");
		JsonObject current = byHash.isEmpty() ? new JsonObject() : postJson(MODRINTH + "/version_files", hashes).getAsJsonObject();
		JsonObject query = hashes.deepCopy();
		JsonArray loaders = new JsonArray();
		loaders.add("fabric");
		JsonArray games = new JsonArray();
		games.add(targetMc);
		query.add("loaders", loaders);
		query.add("game_versions", games);
		JsonObject updates = byHash.isEmpty() ? new JsonObject() : postJson(MODRINTH + "/version_files/update", query).getAsJsonObject();

		for (ModCheck m : mods) {
			if (m.file.startsWith("nice-control-center-")) {
				companionCheck(m, targetMc);
				loaderCheck(m, targetLoader);
				continue;
			}
			if (!current.has(m.sha1)) {
				m.status = "unknown";
				m.action = "keep";
				continue;
			}
			JsonObject now = current.getAsJsonObject(m.sha1);
			boolean nowSupports = contains(now.getAsJsonArray("game_versions"), targetMc);
			if (updates.has(m.sha1)) {
				JsonObject next = updates.getAsJsonObject(m.sha1);
				JsonObject file = primaryFile(next);
				if (file == null) {
					m.status = "unknown";
					m.action = "keep";
					continue;
				}
				boolean same = file.getAsJsonObject("hashes").get("sha1").getAsString().equalsIgnoreCase(m.sha1);
				if (same) {
					m.status = "ok";
					m.action = "keep";
				} else {
					m.status = "update";
					m.action = "update";
					m.newVersion = next.get("version_number").getAsString();
					// A plain file name in mods/, whatever Modrinth sends.
					m.newFile = Path.of(file.get("filename").getAsString().replace('\\', '/')).getFileName().toString();
					if (!m.newFile.endsWith(".jar") || m.newFile.startsWith(".")) {
						m.status = "unknown";
						m.action = "keep";
						continue;
					}
					m.url = file.get("url").getAsString();
					m.newSha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
				}
			} else if (nowSupports) {
				m.status = "ok";
				m.action = "keep";
			} else {
				m.status = "missing";
				// Within the same Minecraft version nothing changes for the mod, so it stays.
				m.action = sameMc ? "keep" : "disable";
			}
		}
		for (ModCheck m : mods) if (!m.file.startsWith("nice-control-center-")) loaderCheck(m, targetLoader);
		return mods;
	}

	/** Only the common forms ">=x", ">x", "x" and "*" are understood; anything else isn't judged. */
	private static void loaderCheck(ModCheck m, String targetLoader) {
		if (targetLoader == null || m.loaderDep == null) return;
		for (String part : m.loaderDep.split("\\s+")) {
			String p = part.strip();
			boolean ok;
			if (p.isEmpty() || p.equals("*")) continue;
			if (p.startsWith(">=")) ok = Companion.compare(targetLoader, p.substring(2)) >= 0;
			else if (p.startsWith(">")) ok = Companion.compare(targetLoader, p.substring(1)) > 0;
			else if (Character.isDigit(p.charAt(0))) ok = Companion.compare(targetLoader, p) >= 0;
			else continue;
			if (!ok) {
				m.needsLoader = p;
				return;
			}
		}
	}

	private void companionCheck(ModCheck m, String targetMc) {
		m.status = "companion";
		m.action = "keep";
		try {
			Companion.Release r = companion.latest(targetMc);
			if (r == null) {
				m.status = "missing";
				m.action = "disable";
			} else {
				m.newVersion = r.version();
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			// Checked again before the next start.
		}
	}

	private static void readModJson(Path jar, ModCheck m) {
		m.name = jar.getFileName().toString();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.getEntry("fabric.mod.json");
			if (entry == null) return;
			try (InputStream in = zip.getInputStream(entry)) {
				JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				if (json.has("id")) m.id = json.get("id").getAsString();
				if (json.has("name")) m.name = json.get("name").getAsString();
				if (json.has("version")) m.version = json.get("version").getAsString();
				if (json.has("depends") && json.get("depends").isJsonObject()) {
					JsonElement dep = json.getAsJsonObject("depends").get("fabricloader");
					if (dep != null && dep.isJsonPrimitive()) m.loaderDep = dep.getAsString();
					else if (dep != null && dep.isJsonArray() && !dep.getAsJsonArray().isEmpty()) m.loaderDep = dep.getAsJsonArray().get(0).getAsString();
				}
			}
		} catch (IOException | RuntimeException e) {
			// File name only.
		}
	}

	// ── Updating ───────────────────────────────────────────────────────────

	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", running);
		out.put("phase", phase);
		out.put("log", log);
		out.put("result", result);
		out.put("history", history());
		return out;
	}

	/** Starts the update in the background. actions: file name → keep/update/disable. Returns an error or null. */
	public synchronized String start(String targetMc, String loader, Map<String, String> actions, String by) {
		if (running) return "An update is already running.";
		if (backups.busy()) return "A backup is running; try again in a moment.";
		if (targetMc == null || loader == null || targetMc.isBlank() || loader.isBlank()) return "Choose a Minecraft and a Fabric Loader version.";
		// Both end up in a download URL and in the launcher's file name.
		if (!VERSION.matcher(targetMc).matches() || !VERSION.matcher(loader).matches()) return "That doesn't look like a version number.";
		running = true;
		log.clear();
		result = null;
		Thread t = new Thread(() -> {
			try {
				result = run(targetMc, loader, actions == null ? Map.of() : actions, by);
			} catch (InterruptedException e) {
				result = "Interrupted";
				Thread.currentThread().interrupt();
			} catch (IOException | RuntimeException e) {
				result = "Failed: " + e.getMessage();
				step(result);
			} finally {
				phase = "";
				running = false;
			}
		}, "version-update");
		t.setDaemon(true);
		t.start();
		return null;
	}

	private String run(String targetMc, String loader, Map<String, String> actions, String by) throws IOException, InterruptedException {
		Installed before = installed();
		String from = "Minecraft " + before.mc() + ", Fabric Loader " + before.loader();
		String to = "Minecraft " + targetMc + ", Fabric Loader " + loader;
		step("Updating from " + from + " to " + to);
		audit.log(by, "versions.update", from + " → " + to, null);

		phase = "Checking mods";
		List<ModCheck> mods = check(targetMc, loader);
		for (ModCheck m : mods) {
			String chosen = actions.get(m.file);
			if (chosen != null && List.of("keep", "update", "disable").contains(chosen)) m.action = chosen;
			if (m.action.equals("update") && m.url == null) m.action = "keep";
		}

		phase = "Backing up";
		step("Making a backup first");
		String error = backups.create("before update to " + targetMc + " " + loader, by);
		if (error != null) return finish(from, to, by, null, "Stopped: the backup failed (" + error + "), nothing was changed.");
		String backup = backups.lastCreated();
		backups.protect(backup);
		step("Backup " + backup);

		phase = "Downloading";
		String installer = latestInstaller();
		String jarName = "fabric-server-mc." + targetMc + "-loader." + loader + "-launcher." + installer + ".jar";
		Path tmpDir = serverDir.resolve(ServerFiles.TMP).resolve("update");
		Files.createDirectories(tmpDir);
		Path launcherTmp = tmpDir.resolve(jarName);
		download(META + "/loader/" + targetMc + "/" + loader + "/" + installer + "/server/jar", launcherTmp, null);
		step("Downloaded " + jarName);
		Map<ModCheck, Path> downloaded = new LinkedHashMap<>();
		for (ModCheck m : mods) {
			if (!m.action.equals("update")) continue;
			Path target = tmpDir.resolve(m.newFile);
			download(m.url, target, m.newSha1);
			downloaded.put(m, target);
			step("Downloaded " + m.name + " " + m.newVersion);
		}

		phase = "Stopping the server";
		if (server.running()) {
			step("Stopping the server");
			server.stop();
			if (!server.awaitState(10 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED)) {
				return finish(from, to, by, backup, "Stopped: the server didn't stop within 10 minutes; nothing was changed.");
			}
		}
		server.stop();

		phase = "Swapping files";
		Path modsDir = serverDir.resolve("mods");
		for (Map.Entry<ModCheck, Path> d : downloaded.entrySet()) {
			Files.deleteIfExists(modsDir.resolve(d.getKey().file));
			Files.move(d.getValue(), modsDir.resolve(d.getKey().newFile), StandardCopyOption.REPLACE_EXISTING);
			step("Updated " + d.getKey().name + " → " + d.getKey().newVersion);
		}
		// Fabric only loads *.jar, so a .disabled ending turns a mod off; renaming it back turns it on.
		for (ModCheck m : mods) {
			if (!m.action.equals("disable") || !Files.exists(modsDir.resolve(m.file))) continue;
			Files.move(modsDir.resolve(m.file), modsDir.resolve(m.file + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
			step("Turned off " + m.name + " (" + m.file + ".disabled)");
		}
		Files.move(launcherTmp, serverDir.resolve(jarName), StandardCopyOption.REPLACE_EXISTING);
		String oldJar = before.jar();
		setServerJar.accept(jarName);
		if (!oldJar.equals(jarName)) Files.deleteIfExists(serverDir.resolve(oldJar));
		step("Server jar is now " + jarName);

		phase = "Starting";
		step("Starting the server (the first start of a new version downloads and prepares Minecraft, which can take a few minutes)");
		error = server.start();
		boolean up = error == null && server.awaitState(15 * 60_000L, Supervisor.State.RUNNING, Supervisor.State.CRASHED, Supervisor.State.STOPPED)
				&& server.state() == Supervisor.State.RUNNING;
		if (up) {
			step("The server is running on the new version");
			return finish(from, to, by, backup, "Done");
		}

		phase = "Rolling back";
		step("The server didn't start" + (error == null ? "" : " (" + error + ")") + ". Restoring the backup " + backup);
		server.stop();
		server.awaitState(5 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED);
		server.stop();
		String restoreError = backups.restore(backup, List.of(), by);
		if (restoreError != null) return finish(from, to, by, backup, "The update failed and the restore failed too: " + restoreError);
		setServerJar.accept(oldJar);
		step("Restored; starting the old version again");
		server.start();
		return finish(from, to, by, backup, "Rolled back: the new version didn't start, so the backup was restored. The console shows why.");
	}

	private String finish(String from, String to, String by, String backup, String outcome) {
		step(outcome);
		backups.unprotect(backup);
		List<HistoryEntry> h = new ArrayList<>(history());
		h.addFirst(new HistoryEntry(System.currentTimeMillis(), from, to, by, backup, outcome));
		while (h.size() > 30) h.removeLast();
		try {
			Files.createDirectories(historyFile.getParent());
			Files.writeString(historyFile, Json.GSON.toJson(h), StandardCharsets.UTF_8);
		} catch (IOException e) {
			// History only.
		}
		audit.log(by, "versions.result", to + ": " + outcome, null);
		return outcome;
	}

	/** Undo an update: stop, restore the backup made before it, switch back to its launcher, start. */
	public synchronized String undo(String backup, String by) {
		if (running) return "An update is already running.";
		if (!backups.exists(backup)) return "That backup doesn't exist anymore.";
		running = true;
		log.clear();
		result = null;
		Thread t = new Thread(() -> {
			try {
				phase = "Undoing";
				audit.log(by, "versions.undo", backup, null);
				step("Undoing the update with backup " + backup);
				String jar = null;
				for (Map<String, Object> c : backups.contents(backup)) {
					String p = (String) c.get("path");
					if (LAUNCHER.matcher(p).matches()) jar = p;
				}
				if (server.running()) {
					server.stop();
					server.awaitState(10 * 60_000L, Supervisor.State.STOPPED, Supervisor.State.CRASHED);
				}
				server.stop();
				String error = backups.restore(backup, List.of(), by);
				if (error != null) {
					result = "Undo failed: " + error;
					step(result);
					return;
				}
				if (jar != null) setServerJar.accept(jar);
				step("Restored" + (jar == null ? "" : "; server jar is " + jar) + ". Starting the server");
				server.start();
				result = "Undone";
				step(result);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException | RuntimeException e) {
				result = "Undo failed: " + e.getMessage();
				step(result);
			} finally {
				phase = "";
				running = false;
			}
		}, "version-undo");
		t.setDaemon(true);
		t.start();
		return null;
	}

	public List<HistoryEntry> history() {
		try {
			if (Files.exists(historyFile)) {
				List<HistoryEntry> h = Json.GSON.fromJson(Files.readString(historyFile), new TypeToken<List<HistoryEntry>>() { }.getType());
				if (h != null) return h;
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return List.of();
	}

	private void step(String text) {
		log.add(text);
		server.note("Update: " + text);
	}

	// ── HTTP ───────────────────────────────────────────────────────────────

	private HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Explorers-Eden/nice-control-center-panel/" + Panel.VERSION + " (github.com/Explorers-Eden/Nice-Control-Center)");
	}

	private JsonElement getJson(String url) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException(url + " answered " + r.statusCode());
		return JsonParser.parseString(r.body());
	}

	private JsonElement postJson(String url, JsonObject body) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(request(url).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
				HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException("Modrinth answered " + r.statusCode());
		return JsonParser.parseString(r.body());
	}

	private void download(String url, Path target, String expectedSha1) throws IOException, InterruptedException {
		HttpResponse<InputStream> r = http.send(request(url).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) throw new IOException("Download failed (" + r.statusCode() + "): " + url);
		try (InputStream in = r.body()) {
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		if (expectedSha1 != null && !sha1(target).equalsIgnoreCase(expectedSha1)) {
			Files.deleteIfExists(target);
			throw new IOException("The download of " + target.getFileName() + " was damaged (checksum mismatch).");
		}
	}

	private static JsonObject primaryFile(JsonObject version) {
		JsonArray files = version.getAsJsonArray("files");
		JsonObject first = null;
		for (JsonElement f : files) {
			if (first == null) first = f.getAsJsonObject();
			if (f.getAsJsonObject().get("primary").getAsBoolean()) return f.getAsJsonObject();
		}
		return first;
	}

	private static boolean contains(JsonArray array, String value) {
		for (JsonElement e : array) if (e.getAsString().equals(value)) return true;
		return false;
	}

	static String sha1(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
