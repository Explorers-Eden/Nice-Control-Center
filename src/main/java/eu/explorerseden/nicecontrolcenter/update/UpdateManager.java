package eu.explorerseden.nicecontrolcenter.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * Keeps mods and data packs up to date from Modrinth (found by file hash, no setup) and GitHub
 * releases (configured per mod/pack).
 *
 * <p>Updates are downloaded and checked in the background, then installed when the server stops,
 * so they take effect at the next start (mod jars can't be swapped while they are loaded). The
 * replaced files are kept as backups for rollback; no world backups are made.
 */
public final class UpdateManager {
	public enum Status {
		UP_TO_DATE, AVAILABLE, STAGED, PENDING, HELD, SKIPPED, IGNORED, NOT_FOUND, FAILED
	}

	/** One installed mod or data pack, as shown in the dashboard. */
	public static final class Entry {
		public String key;
		public String kind;
		public String name;
		public String file;
		public String installedVersion;
		public String source;
		public String projectUrl;
		public String latestVersion;
		public String latestVersionId;
		public String latestFile;
		public String changelog;
		public String published;
		public Status status;
		public String reason;
		/** "url|sha512" or "url|size:bytes"; not sent to the dashboard. */
		transient String download;
	}

	/** A file move to do at the next stop: install a downloaded update, or restore a backup. */
	public static final class Pending {
		public String type;
		public String key;
		public String name;
		public String kind;
		public String from;
		public String targetDir;
		public String removeFile;
		public String newFile;
		public String oldVersion;
		public String newVersion;
		public String newVersionId;
		public boolean approved;
		/** For type "properties": server.properties keys to set. */
		public Map<String, String> values;
	}

	/** Saved between restarts. */
	private static final class State {
		Map<String, String> skipped = new HashMap<>();
		Set<String> ignored = new HashSet<>();
		List<Pending> pending = new ArrayList<>();
		long lastCheck;
		String lastError;
		List<String> lastApplied = new ArrayList<>();
		ResourcePack.Cache pack;
		long packLastCheck;
		String packError;
	}

	public record Backup(String id, long time, List<Map<String, String>> files) {
	}

	private static final DateTimeFormatter BATCH = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static ScheduledExecutorService executor;
	private static ScheduledFuture<?> schedule;
	private static volatile List<Entry> entries = List.of();
	private static volatile Entry packEntry;
	private static volatile boolean checking;
	private static volatile boolean packChecking;
	private static ScheduledFuture<?> packSchedule;
	private static MinecraftServer server;
	private static ControlCenterConfig config;
	private static State state = new State();
	private static Path base;
	private static Path datapackDir;
	private static boolean dedicated;
	/** The singleplayer "install when the game quits" hook is added once per game, not per world. */
	private static boolean shutdownHook;

	private UpdateManager() {
	}

	// ── Lifecycle ───────────────────────────────────────────────────────────

	public static synchronized void start(MinecraftServer server, ControlCenterConfig config) {
		base = server.getServerDirectory().resolve("nicecontrolcenter").resolve("updates");
		datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
		dedicated = server.isDedicatedServer();
		UpdateManager.server = server;
		UpdateManager.config = config;
		packEntry = null;
		load();
		reportLastApply();
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Nice Control Center Updater");
			thread.setDaemon(true);
			return thread;
		});
		if (!"off".equals(mode(config))) {
			long hours = Math.max(1, config.update_check_hours);
			schedule = executor.scheduleWithFixedDelay(() -> check(config), 60, hours * 3600, TimeUnit.SECONDS);
			if (dedicated && config.resource_pack_check_minutes > 0) {
				packSchedule = executor.scheduleWithFixedDelay(() -> checkPack(config), 20, config.resource_pack_check_minutes * 60L,
						TimeUnit.SECONDS);
			}
		}
	}

	/** Server stop: installs approved updates and restores, then stops the background thread. */
	public static synchronized void stop() {
		if (schedule != null) {
			schedule.cancel(false);
		}
		if (packSchedule != null) {
			packSchedule.cancel(false);
			packSchedule = null;
		}
		server = null;
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		if (dedicated) {
			applyPending();
		} else {
			// Singleplayer: the game keeps running after the world closes; mods are swapped when it quits.
			if (!shutdownHook) {
				shutdownHook = true;
				Runtime.getRuntime().addShutdownHook(new Thread(UpdateManager::applyPending, "Nice Control Center Update Install"));
			}
		}
	}

	/** Mods and data packs, with the server resource pack first if there is one. */
	public static List<Entry> entries() {
		Entry pack = packEntry;
		if (pack == null) {
			return entries;
		}
		List<Entry> all = new ArrayList<>(entries.size() + 1);
		all.add(pack);
		all.addAll(entries);
		return all;
	}

	public static boolean checking() {
		return checking || packChecking;
	}

	/** server.properties values the updater will set at the next stop, by key. */
	public static synchronized Map<String, String> pendingProperties() {
		Map<String, String> result = new LinkedHashMap<>();
		state.pending.stream().filter(p -> "properties".equals(p.type) && p.approved && p.values != null).forEach(p -> result.putAll(p.values));
		return result;
	}

		public static synchronized Map<String, Object> summary(ControlCenterConfig config) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", mode(config));
		result.put("checking", checking());
		result.put("lastCheck", state.lastCheck);
		result.put("lastError", state.lastError);
		result.put("lastApplied", state.lastApplied);
		result.put("entries", entries());
		result.put("packSource", packSource(config));
		result.put("packConfigured", !config.resource_pack_source.isBlank());
		result.put("packLastCheck", state.packLastCheck);
		result.put("packError", state.packError);
		result.put("packWarning", state.pack == null ? null : state.pack.warning);
		result.put("webhook", !config.resource_pack_webhook_token.isBlank());
		result.put("pending", state.pending);
		result.put("backups", backups());
		result.put("dedicated", dedicated);
		return result;
	}

	// ── Checking ────────────────────────────────────────────────────────────

	/** Runs a check of everything in the background now. */
	public static void checkNow(ControlCenterConfig config) {
		if (executor != null && !checking) {
			executor.execute(() -> check(config));
			if (dedicated) {
				executor.execute(() -> checkPack(config));
			}
		}
	}

	/** Checks only the server resource pack now (dashboard button, build ping). */
	public static void checkPackNow(ControlCenterConfig config) {
		if (executor != null && dedicated && !packChecking) {
			executor.execute(() -> checkPack(config));
		}
	}

	// ── Server resource pack ────────────────────────────────────────────────

	/** The configured source, or the link already in server.properties. Empty = nothing to watch. */
	private static String packSource(ControlCenterConfig config) {
		if (!config.resource_pack_source.isBlank()) {
			return config.resource_pack_source.trim();
		}
		MinecraftServer current = server;
		String link = current == null ? null : eu.explorerseden.nicecontrolcenter.server.PropertiesEditor.get(current, "resource-pack");
		return link == null ? "" : link.trim();
	}

	private static void checkPack(ControlCenterConfig config) {
		MinecraftServer current = server;
		String source = packSource(config);
		if (current == null || source.isEmpty() || "off".equals(mode(config)) || packChecking) {
			packEntry = null;
			return;
		}
		packChecking = true;
		try {
			String url = eu.explorerseden.nicecontrolcenter.server.PropertiesEditor.get(current, "resource-pack");
			String sha1 = eu.explorerseden.nicecontrolcenter.server.PropertiesEditor.get(current, "resource-pack-sha1").toLowerCase(Locale.ROOT);
			ResourcePack.Cache cache;
			synchronized (UpdateManager.class) {
				cache = state.pack;
			}
			cache = ResourcePack.resolve(source, config.update_channels, cache, base.resolve("staged"));

			Entry entry = new Entry();
			entry.key = "resourcepack";
			entry.kind = "resourcepack";
			entry.name = "Server resource pack";
			entry.file = cache.url;
			entry.source = source.startsWith("github:") ? "github" : source.startsWith("modrinth:") ? "modrinth" : "url";
			entry.projectUrl = cache.projectUrl;
			entry.installedVersion = sha1.isEmpty() ? "no SHA-1 set" : "SHA-1 " + sha1.substring(0, Math.min(8, sha1.length()));
			entry.latestVersion = cache.version + " · SHA-1 " + cache.sha1.substring(0, 8);
			entry.latestVersionId = cache.sha1;
			entry.latestFile = cache.url;
			entry.changelog = cache.changelog;
			entry.published = cache.published;
			boolean same = cache.sha1.equalsIgnoreCase(sha1) && cache.url.equals(url);
			entry.status = same ? Status.UP_TO_DATE : Status.AVAILABLE;
			if (!same && sha1.isEmpty() && cache.url.equals(url)) {
				entry.reason = "server.properties has no resource-pack-sha1, so players download the pack on every join.";
			} else if (!same && cache.url.equals(url)) {
				entry.reason = "The pack behind the link changed; players get it after the restart.";
			} else if (!same) {
				entry.reason = "server.properties gets the new link and SHA-1 at the restart.";
			}
			if (cache.warning != null) {
				entry.reason = (entry.reason == null ? "" : entry.reason + " ") + "Note: " + cache.warning + ".";
			}

			List<String> staged = new ArrayList<>();
			synchronized (UpdateManager.class) {
				state.pack = cache;
				state.packLastCheck = System.currentTimeMillis();
				state.packError = null;
				if (state.ignored.contains(entry.key)) {
					entry.status = Status.IGNORED;
					state.pending.removeIf(p -> p.key.equals(entry.key));
				} else if (entry.status == Status.UP_TO_DATE) {
					state.pending.removeIf(p -> p.key.equals(entry.key));
				} else if (cache.sha1.equals(state.skipped.get(entry.key))) {
					entry.status = Status.SKIPPED;
				} else if ("auto".equals(mode(config)) || "stage".equals(mode(config))) {
					Pending existing = state.pending.stream().filter(p -> p.key.equals(entry.key)).findFirst().orElse(null);
					if (existing == null || !cache.sha1.equals(existing.newVersionId) || !cache.url.equals(existing.newFile)) {
						state.pending.removeIf(p -> p.key.equals(entry.key));
						Pending pending = new Pending();
						pending.type = "properties";
						pending.key = entry.key;
						pending.name = entry.name;
						pending.kind = entry.kind;
						pending.from = "";
						pending.targetDir = eu.explorerseden.nicecontrolcenter.server.PropertiesEditor.file(current).toAbsolutePath().toString();
						pending.removeFile = url;
						pending.newFile = cache.url;
						pending.oldVersion = entry.installedVersion;
						pending.newVersion = entry.latestVersion;
						pending.newVersionId = cache.sha1;
						pending.values = new LinkedHashMap<>();
						pending.values.put("resource-pack", cache.url);
						pending.values.put("resource-pack-sha1", cache.sha1);
						pending.approved = "auto".equals(mode(config));
						state.pending.add(pending);
						staged.add(entry.name + " → " + entry.latestVersion);
					}
					entry.status = state.pending.stream().anyMatch(p -> p.key.equals(entry.key) && p.approved) ? Status.PENDING : Status.STAGED;
				}
				packEntry = entry;
				save();
			}
			if (!staged.isEmpty()) {
				NiceControlCenter.notifyAdmins(net.minecraft.network.chat.Component.literal("Nice Control Center · new server resource pack ("
						+ cache.version + "). " + ("auto".equals(mode(config)) ? "It is set in server.properties at the next restart."
								: "Approve it in the dashboard (Updates tab).")), "New server resource pack: " + cache.version + " sha1 " + cache.sha1);
			}
		} catch (IOException | RuntimeException e) {
			synchronized (UpdateManager.class) {
				state.packError = e.getMessage();
				state.packLastCheck = System.currentTimeMillis();
				save();
			}
			Entry failed = new Entry();
			failed.key = "resourcepack";
			failed.kind = "resourcepack";
			failed.name = "Server resource pack";
			failed.file = source;
			failed.status = Status.FAILED;
			failed.reason = "Check failed: " + e.getMessage();
			packEntry = failed;
			NiceControlCenter.LOGGER.warn("Resource pack check failed: {}", e.getMessage());
		} finally {
			packChecking = false;
		}
	}

	private static void check(ControlCenterConfig config) {
		if (checking) {
			return;
		}
		checking = true;
		try {
			String gameVersion = SharedConstants.getCurrentVersion().name();
			List<Installed.Item> items = Installed.scan(datapackDir);
			Map<String, Installed.Item> bySha = new LinkedHashMap<>();
			items.forEach(i -> bySha.put(i.sha1(), i));

			Map<String, JsonObject> current = Sources.modrinthCurrent(bySha.keySet());
			Set<String> modShas = new HashSet<>();
			Set<String> packShas = new HashSet<>();
			items.forEach(i -> (i.kind().equals("mod") ? modShas : packShas).add(i.sha1()));
			Map<String, JsonObject> latest = new HashMap<>();
			if (!modShas.isEmpty()) {
				latest.putAll(Sources.modrinthLatest(modShas, "fabric", gameVersion));
			}
			if (!packShas.isEmpty()) {
				latest.putAll(Sources.modrinthLatest(packShas, "datapack", gameVersion));
			}
			Set<String> projectIds = new HashSet<>();
			current.values().forEach(v -> projectIds.add(v.get("project_id").getAsString()));
			Map<String, JsonObject> projects = Sources.modrinthProjects(projectIds);
			Set<String> installedSlugs = new HashSet<>();
			projects.values().forEach(p -> installedSlugs.add(p.get("id").getAsString()));
			items.forEach(i -> installedSlugs.add(i.id()));

			List<Entry> result = new ArrayList<>();
			for (Installed.Item item : items) {
				Entry entry = new Entry();
				entry.key = item.key();
				entry.kind = item.kind();
				entry.name = item.name();
				entry.file = item.file().getFileName().toString();
				entry.installedVersion = item.version();
				String repo = config.update_github.get(item.id());
				JsonObject now = current.get(item.sha1());
				if (now != null) {
					modrinthEntry(entry, item, now, latest.get(item.sha1()), projects, installedSlugs, config, gameVersion);
				} else if (repo != null) {
					githubEntry(entry, item, repo, gameVersion);
				} else {
					entry.source = "none";
					entry.status = Status.NOT_FOUND;
					entry.reason = "Not found on Modrinth; add it to update_github to update it from GitHub.";
				}
				synchronized (UpdateManager.class) {
					if (state.ignored.contains(entry.key) || config.update_ignore.contains(item.id())) {
						entry.status = Status.IGNORED;
					} else if (entry.latestVersionId != null && entry.latestVersionId.equals(state.skipped.get(entry.key))
							&& entry.status == Status.AVAILABLE) {
						entry.status = Status.SKIPPED;
					}
				}
				result.add(entry);
			}
			result.sort(Comparator.comparing((Entry e) -> e.status.ordinal() == 1 ? 0 : 1).thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
			stageAvailable(result, items, config);
			synchronized (UpdateManager.class) {
				entries = result;
				state.lastCheck = System.currentTimeMillis();
				state.lastError = null;
				markPending(result);
				save();
			}
		} catch (IOException | RuntimeException e) {
			synchronized (UpdateManager.class) {
				state.lastError = e.getMessage();
				save();
			}
			NiceControlCenter.LOGGER.warn("Update check failed: {}", e.getMessage());
		} finally {
			checking = false;
		}
	}

	private static void modrinthEntry(Entry entry, Installed.Item item, JsonObject now, JsonObject newest, Map<String, JsonObject> projects,
			Set<String> installedProjects, ControlCenterConfig config, String gameVersion) throws IOException {
		String projectId = now.get("project_id").getAsString();
		JsonObject project = projects.get(projectId);
		entry.source = "modrinth";
		entry.projectUrl = "https://modrinth.com/project/" + (project != null ? project.get("slug").getAsString() : projectId);
		if (project != null) {
			entry.name = project.get("title").getAsString();
		}
		entry.installedVersion = now.get("version_number").getAsString();
		if (newest != null && !config.update_channels.contains(str(newest, "version_type"))) {
			// The newest is a beta/alpha; look for the newest allowed one.
			newest = null;
			for (JsonElement v : Sources.modrinthVersions(projectId, item.kind().equals("mod") ? "fabric" : "datapack", gameVersion)) {
				if (config.update_channels.contains(str(v.getAsJsonObject(), "version_type"))) {
					newest = v.getAsJsonObject();
					break;
				}
			}
		}
		if (newest == null || newest.get("id").getAsString().equals(now.get("id").getAsString())) {
			entry.status = Status.UP_TO_DATE;
			return;
		}
		if (!newest.get("date_published").getAsString().isEmpty()
				&& newest.get("date_published").getAsString().compareTo(now.get("date_published").getAsString()) <= 0) {
			entry.status = Status.UP_TO_DATE;
			return;
		}
		entry.latestVersion = newest.get("version_number").getAsString();
		entry.latestVersionId = newest.get("id").getAsString();
		entry.changelog = str(newest, "changelog");
		entry.published = str(newest, "date_published");
		JsonObject file = primaryFile(newest.getAsJsonArray("files"));
		if (file == null) {
			entry.status = Status.FAILED;
			entry.reason = "The new version has no file to download.";
			return;
		}
		entry.latestFile = file.get("filename").getAsString();
		for (JsonElement dep : newest.getAsJsonArray("dependencies")) {
			JsonObject d = dep.getAsJsonObject();
			if ("required".equals(str(d, "dependency_type")) && d.has("project_id") && !d.get("project_id").isJsonNull()
					&& !installedProjects.contains(d.get("project_id").getAsString())) {
				entry.status = Status.HELD;
				entry.reason = "The new version needs another mod or pack that isn't installed (Modrinth project " + d.get("project_id").getAsString() + ").";
				return;
			}
		}
		entry.status = Status.AVAILABLE;
		entry.download = file.get("url").getAsString() + "|" + file.getAsJsonObject("hashes").get("sha512").getAsString();
	}

	private static void githubEntry(Entry entry, Installed.Item item, String repo, String gameVersion) throws IOException {
		entry.source = "github";
		entry.projectUrl = "https://github.com/" + repo;
		JsonObject release = Sources.githubLatest(repo);
		if (release == null) {
			entry.status = Status.NOT_FOUND;
			entry.reason = "No release found in " + repo + ".";
			return;
		}
		String extension = item.kind().equals("mod") ? ".jar" : ".zip";
		JsonObject asset = null;
		for (JsonElement element : release.getAsJsonArray("assets")) {
			JsonObject candidate = element.getAsJsonObject();
			String name = str(candidate, "name");
			if (name.endsWith(extension) && (asset == null || name.contains(gameVersion))) {
				asset = candidate;
			}
		}
		String tag = str(release, "tag_name");
		entry.latestVersion = tag;
		entry.latestVersionId = tag;
		entry.changelog = str(release, "body");
		entry.published = str(release, "published_at");
		if (asset == null) {
			entry.status = Status.FAILED;
			entry.reason = "The latest release has no " + extension + " file.";
			return;
		}
		entry.latestFile = str(asset, "name");
		boolean same = entry.latestFile.equals(entry.file)
				|| (!item.version().isEmpty() && normaliseTag(tag).equals(item.version()));
		if (same) {
			entry.status = Status.UP_TO_DATE;
			return;
		}
		entry.status = Status.AVAILABLE;
		entry.download = str(asset, "browser_download_url") + "|size:" + asset.get("size").getAsLong();
	}

	private static String normaliseTag(String tag) {
		String t = tag.replaceFirst("^(mod-)?v", "");
		int mc = t.indexOf("-mc");
		return mc > 0 ? t.substring(0, mc) : t;
	}

	private static JsonObject primaryFile(JsonArray files) {
		JsonObject first = null;
		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();
			if (first == null) {
				first = file;
			}
			if (file.has("primary") && file.get("primary").getAsBoolean()) {
				return file;
			}
		}
		return first;
	}

	// ── Staging ─────────────────────────────────────────────────────────────

	/** Downloads available updates (modes auto and stage) and checks them. */
	private static void stageAvailable(List<Entry> list, List<Installed.Item> items, ControlCenterConfig config) {
		if (!"auto".equals(mode(config)) && !"stage".equals(mode(config))) {
			return;
		}
		Map<String, Installed.Item> byKey = new HashMap<>();
		items.forEach(i -> byKey.put(i.key(), i));
		List<String> staged = new ArrayList<>();
		for (Entry entry : list) {
			if (entry.status != Status.AVAILABLE) {
				continue;
			}
			synchronized (UpdateManager.class) {
				if (state.pending.stream().anyMatch(p -> p.key.equals(entry.key) && entry.latestVersionId.equals(p.newVersionId))) {
					entry.status = Status.PENDING;
					continue;
				}
			}
			Installed.Item item = byKey.get(entry.key);
			try {
				Path file = download(entry, item);
				Pending pending = new Pending();
				pending.type = "install";
				pending.key = entry.key;
				pending.name = entry.name;
				pending.kind = entry.kind;
				pending.from = file.toString();
				pending.targetDir = item.file().getParent().toString();
				pending.removeFile = item.file().getFileName().toString();
				pending.newFile = entry.latestFile;
				pending.oldVersion = entry.installedVersion;
				pending.newVersion = entry.latestVersion;
				pending.newVersionId = entry.latestVersionId;
				pending.approved = "auto".equals(mode(config));
				synchronized (UpdateManager.class) {
					state.pending.removeIf(p -> p.key.equals(entry.key) && p.type.equals("install"));
					state.pending.add(pending);
				}
				entry.status = pending.approved ? Status.PENDING : Status.STAGED;
				staged.add(entry.name + " " + entry.installedVersion + " → " + entry.latestVersion);
			} catch (IOException | RuntimeException e) {
				entry.status = Status.FAILED;
				entry.reason = "Download or check failed: " + e.getMessage();
			}
		}
		if (!staged.isEmpty()) {
			String when = dedicated ? "the next restart" : "the next time the game is closed";
			NiceControlCenter.notifyAdmins(net.minecraft.network.chat.Component.literal("Nice Control Center · "
					+ staged.size() + (staged.size() == 1 ? " update" : " updates") + " downloaded: " + String.join(", ", staged)
					+ ("auto".equals(mode(config)) ? ". They will be installed at " + when + "." : ". Approve them in the dashboard (Updates tab).")),
					"Downloaded updates: " + String.join(", ", staged));
		}
	}

	private static Path download(Entry entry, Installed.Item item) throws IOException {
		String[] info = entry.download.split("\\|", 2);
		Path dir = base.resolve("staged");
		Files.createDirectories(dir);
		Path target = dir.resolve(entry.key.replaceAll("[^A-Za-z0-9._-]", "_") + "__" + entry.latestFile.replaceAll("[^A-Za-z0-9._+-]", "_"));
		Sources.download(info[0], target);
		String expected = info.length > 1 ? info[1] : "";
		if (expected.startsWith("size:")) {
			if (Files.size(target) != Long.parseLong(expected.substring(5))) {
				Files.deleteIfExists(target);
				throw new IOException("size doesn't match");
			}
		} else if (!expected.isEmpty()) {
			String[] hashes = Installed.hashes(target);
			if (hashes == null || !hashes[1].equalsIgnoreCase(expected)) {
				Files.deleteIfExists(target);
				throw new IOException("checksum doesn't match");
			}
		}
		verifyContent(target, item);
		return target;
	}

	/** A mod jar must be the same mod; a data pack zip must be a data pack. */
	private static void verifyContent(Path file, Installed.Item item) throws IOException {
		try (ZipFile zip = new ZipFile(file.toFile())) {
			if (item.kind().equals("mod")) {
				ZipEntry meta = zip.getEntry("fabric.mod.json");
				if (meta == null) {
					throw new IOException("not a Fabric mod");
				}
				try (InputStream in = zip.getInputStream(meta)) {
					JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
					if (!item.id().equals(str(json, "id"))) {
						throw new IOException("it is a different mod (" + str(json, "id") + ")");
					}
				}
			} else if (zip.getEntry("pack.mcmeta") == null) {
				throw new IOException("not a data pack");
			}
		} catch (IOException e) {
			Files.deleteIfExists(file);
			throw e;
		} catch (JsonParseException | IllegalStateException e) {
			Files.deleteIfExists(file);
			throw new IOException("unreadable fabric.mod.json", e);
		}
	}

	private static void markPending(List<Entry> list) {
		for (Entry entry : list) {
			for (Pending pending : state.pending) {
				if (pending.key.equals(entry.key) && pending.type.equals("install") && entry.status != Status.IGNORED) {
					entry.status = pending.approved ? Status.PENDING : Status.STAGED;
					entry.latestVersion = pending.newVersion;
				}
			}
		}
	}

	// ── User actions ────────────────────────────────────────────────────────

	/** approve | cancel | skip | ignore | unignore. Returns an error or null. */
	public static synchronized String action(String action, String key) {
		Entry entry = entries().stream().filter(e -> e.key.equals(key)).findFirst().orElse(null);
		switch (action) {
			case "approve" -> state.pending.stream().filter(p -> p.key.equals(key)).forEach(p -> p.approved = true);
			case "cancel" -> state.pending.removeIf(p -> p.key.equals(key));
			case "skip" -> {
				if (entry == null || entry.latestVersionId == null) {
					return "Nothing to skip.";
				}
				state.skipped.put(key, entry.latestVersionId);
				state.pending.removeIf(p -> p.key.equals(key) && p.type.equals("install"));
			}
			case "ignore" -> {
				state.ignored.add(key);
				state.pending.removeIf(p -> p.key.equals(key) && p.type.equals("install"));
			}
			case "unignore" -> state.ignored.remove(key);
			default -> {
				return "Unknown action.";
			}
		}
		if (entry != null) {
			entry.status = switch (action) {
				case "approve" -> Status.PENDING;
				case "cancel" -> entry.latestVersionId != null ? Status.AVAILABLE : entry.status;
				case "skip" -> Status.SKIPPED;
				case "ignore" -> Status.IGNORED;
				default -> entry.status;
			};
		}
		NiceControlCenter.LOGGER.info("Updates: {} {}", action, key);
		save();
		return null;
	}

	/** Puts the files of a backup back at the next stop, and skips the versions it replaced. */
	public static synchronized String rollback(String batch) {
		Path dir = base.resolve("backup").resolve(batch);
		Path manifest = dir.resolve("manifest.json");
		if (!batch.matches("[0-9_-]+") || !Files.exists(manifest)) {
			return "Unknown backup.";
		}
		try (Reader reader = Files.newBufferedReader(manifest)) {
			for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
				JsonObject f = element.getAsJsonObject();
				Pending pending = new Pending();
				pending.type = "restore";
				pending.key = str(f, "key");
				pending.name = str(f, "name");
				pending.kind = str(f, "kind");
				pending.from = dir.resolve(str(f, "removedFile")).toString();
				pending.targetDir = str(f, "targetDir");
				pending.removeFile = str(f, "installedFile");
				pending.newFile = str(f, "removedFile");
				pending.oldVersion = str(f, "newVersion");
				pending.newVersion = str(f, "oldVersion");
				pending.approved = true;
				state.pending.removeIf(p -> p.key.equals(pending.key));
				state.pending.add(pending);
				if (!str(f, "newVersionId").isEmpty()) {
					state.skipped.put(pending.key, str(f, "newVersionId"));
				}
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			return "Could not read the backup: " + e.getMessage();
		}
		NiceControlCenter.LOGGER.info("Updates: rollback of {} will happen at the next restart", batch);
		save();
		return null;
	}

	// ── Installing (at stop) ────────────────────────────────────────────────

	/** Moves approved files into place. Runs when the server stops. */
	static synchronized void applyPending() {
		if (base == null) {
			return;
		}
		List<Pending> todo = state.pending.stream().filter(p -> p.approved).toList();
		if (todo.isEmpty()) {
			return;
		}
		String batch = LocalDateTime.now().format(BATCH);
		Path backupDir = base.resolve("backup").resolve(batch);
		List<Map<String, String>> manifest = new ArrayList<>();
		List<String> done = new ArrayList<>();
		for (Pending p : todo) {
			try {
				if (p.type.equals("properties")) {
					eu.explorerseden.nicecontrolcenter.server.PropertiesEditor.saveFile(Path.of(p.targetDir), p.values);
					state.pending.remove(p);
					done.add("Set " + p.name + " to " + p.newVersion);
					continue;
				}
				Path targetDir = Path.of(p.targetDir);
				Path current = targetDir.resolve(p.removeFile);
				Path source = Path.of(p.from);
				if (!Files.exists(source)) {
					throw new IOException("downloaded file is missing");
				}
				if (p.type.equals("install")) {
					Files.createDirectories(backupDir);
					if (Files.exists(current)) {
						// Old file first: if it can't be moved, don't add the new one (two versions would crash).
						Files.move(current, backupDir.resolve(p.removeFile), StandardCopyOption.REPLACE_EXISTING);
					}
					Files.move(source, targetDir.resolve(p.newFile), StandardCopyOption.REPLACE_EXISTING);
					Map<String, String> entry = new LinkedHashMap<>();
					entry.put("key", p.key);
					entry.put("name", p.name);
					entry.put("kind", p.kind);
					entry.put("targetDir", p.targetDir);
					entry.put("removedFile", p.removeFile);
					entry.put("installedFile", p.newFile);
					entry.put("oldVersion", p.oldVersion);
					entry.put("newVersion", p.newVersion);
					entry.put("newVersionId", p.newVersionId == null ? "" : p.newVersionId);
					manifest.add(entry);
				} else {
					Files.deleteIfExists(current);
					Files.copy(source, targetDir.resolve(p.newFile), StandardCopyOption.REPLACE_EXISTING);
				}
				state.pending.remove(p);
				done.add((p.type.equals("restore") ? "Rolled back " : "Installed ") + p.name + " " + p.oldVersion + " → " + p.newVersion);
			} catch (IOException | RuntimeException e) {
				done.add("Could not " + (p.type.equals("restore") ? "roll back " : p.type.equals("properties") ? "set " : "install ") + p.name + ": " + e.getMessage()
						+ " (will retry at the next stop)");
			}
		}
		if (!manifest.isEmpty()) {
			try (Writer writer = Files.newBufferedWriter(backupDir.resolve("manifest.json"))) {
				Json.GSON.toJson(manifest, writer);
			} catch (IOException e) {
				done.add("Could not write the backup list: " + e.getMessage());
			}
			pruneBackups(NiceControlCenter.config() == null ? 10 : NiceControlCenter.config().update_keep_backups);
		}
		state.lastApplied = done;
		save();
	}

	private static void reportLastApply() {
		if (state.lastApplied.isEmpty()) {
			return;
		}
		String text = String.join("; ", state.lastApplied);
		NiceControlCenter.LOGGER.info("Updates at the last stop: {}", text);
		List<String> applied = state.lastApplied;
		state.lastApplied = new ArrayList<>();
		save();
		NiceControlCenter.notifyLater("Nice Control Center · at the last restart: " + String.join(", ", applied)
				+ ". Backups of the old versions are in the dashboard (Updates tab).");
	}

	private static void pruneBackups(int keep) {
		List<Backup> list = backups();
		for (int i = Math.max(1, keep); i < list.size(); i++) {
			Path dir = base.resolve("backup").resolve(list.get(i).id());
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.delete(path);
					} catch (IOException e) {
						// Leftover file; tried again next time.
					}
				});
			} catch (IOException e) {
				// Leave it.
			}
		}
	}

	public static synchronized List<Backup> backups() {
		List<Backup> result = new ArrayList<>();
		if (base == null) {
			return result;
		}
		Path dir = base.resolve("backup");
		if (!Files.isDirectory(dir)) {
			return result;
		}
		try (Stream<Path> batches = Files.list(dir)) {
			for (Path batch : batches.filter(Files::isDirectory).toList()) {
				Path manifest = batch.resolve("manifest.json");
				if (!Files.exists(manifest)) {
					continue;
				}
				List<Map<String, String>> files = new ArrayList<>();
				try (Reader reader = Files.newBufferedReader(manifest)) {
					for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
						Map<String, String> f = new LinkedHashMap<>();
						element.getAsJsonObject().entrySet().forEach(e -> f.put(e.getKey(), e.getValue().getAsString()));
						files.add(f);
					}
				} catch (IOException | JsonParseException | IllegalStateException e) {
					continue;
				}
				result.add(new Backup(batch.getFileName().toString(), Files.getLastModifiedTime(manifest).toMillis(), files));
			}
		} catch (IOException e) {
			// Nothing to list.
		}
		result.sort(Comparator.comparingLong(Backup::time).reversed());
		return result;
	}

	// ── Saving ──────────────────────────────────────────────────────────────

	private static void load() {
		Path file = base.resolve("state.json");
		state = new State();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				State loaded = Json.GSON.fromJson(reader, State.class);
				if (loaded != null) {
					state = loaded;
					if (state.skipped == null) {
						state.skipped = new HashMap<>();
					}
					if (state.ignored == null) {
						state.ignored = new HashSet<>();
					}
					if (state.pending == null) {
						state.pending = new ArrayList<>();
					}
					if (state.lastApplied == null) {
						state.lastApplied = new ArrayList<>();
					}
				}
			} catch (IOException | JsonParseException e) {
				NiceControlCenter.LOGGER.warn("Could not read {}", file, e);
			}
		}
	}

	private static void save() {
		try {
			Files.createDirectories(base);
			try (Writer writer = Files.newBufferedWriter(base.resolve("state.json"))) {
				Json.GSON.toJson(state, writer);
			}
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the update state", e);
		}
	}

	/**
	 * The update mode in effect. Singleplayer only checks: swapping a player's client mods on their
	 * own could break a modpack.
	 */
	private static String mode(ControlCenterConfig config) {
		String mode = config.update_mode == null ? "auto" : config.update_mode;
		return !dedicated && (mode.equals("auto") || mode.equals("stage")) ? "check" : mode;
	}

	private static String str(JsonObject object, String key) {
		JsonElement e = object.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}
}
