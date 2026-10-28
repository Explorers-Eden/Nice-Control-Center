package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.panel.world.MapRenderer;
import eu.explorerseden.nicecontrolcenter.panel.world.RegionFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * The web map: renders tiles from the region files (in the panel, so the server's tick isn't touched
 * and the map stays up while the server is down), and keeps the mod's overlays (players, world border,
 * GOML claims, waypoint hubs) with a cached copy for when the server is off.
 */
public final class MapService {
	public static final class Settings {
		public boolean enabled = true;
		/** Hub visibility: public, all or none. */
		public String hubs = "public";
		public boolean showPlayers = true;
		public boolean showClaims = true;
		public List<String> hidden = new ArrayList<>();
		/** Dimensions with a roof the game doesn't know about (custom ones like a cave world): drawn below it, like the Nether. */
		public List<String> roofs = new ArrayList<>();
		public String title = "Server map";
		/** The status card on the map: online, players, uptime, next restart and reset. */
		public boolean showStatus = true;
		/** List the names of who's online in the status card. */
		public boolean statusNames = false;
		/** The in-game date and time (Nice Actions calendar, or the vanilla day) in the status card. */
		public boolean showClock = true;
	}

	/** Deepest zoom-out level: one tile covers 512 × 2^5 = 16384 blocks. */
	public static final int MIN_ZOOM = -5;

	private final Path serverDir;
	private final Path mapDir;
	private final Path settingsFile;
	private final Supervisor server;
	private final int modPort;
	private final String secret;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "map");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private volatile Settings settings;
	private volatile JsonObject overlays;
	private volatile Map<String, Integer> colors;
	private volatile boolean colorsFromMod;
	private volatile boolean fetchedColorsThisRun;
	private volatile boolean rerenderAll;
	private volatile String renderStatus = "Waiting";
	private volatile int regionsTotal;
	private volatile int regionsRendered;
	private volatile long lastPass;
	/** Extra layers for the admin map (the world trimmer's preview). */
	private volatile JsonObject trimPreview;

	public MapService(Path serverDir, Path mapDir, Path settingsFile, Supervisor server, int modPort, String secret) {
		this.serverDir = serverDir;
		this.mapDir = mapDir;
		this.settingsFile = settingsFile;
		this.server = server;
		this.modPort = modPort;
		this.secret = secret;
		System.setProperty("java.awt.headless", "true");
		Settings s = null;
		try {
			if (Files.exists(settingsFile)) s = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
			Path cached = mapDir.resolve("overlays.json");
			if (Files.exists(cached)) overlays = JsonParser.parseString(Files.readString(cached)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			System.err.println("Map: could not read saved data: " + e.getMessage());
		}
		settings = s != null ? s : new Settings();
		colors = loadColors();
	}

	public void start() {
		timer.scheduleWithFixedDelay(this::pollOverlays, 5, 10, TimeUnit.SECONDS);
		timer.scheduleWithFixedDelay(this::renderPass, 20, 30, TimeUnit.SECONDS);
	}

	public Settings settings() {
		return settings;
	}

	public void save(Settings next) throws IOException {
		if (next.hidden == null) next.hidden = new ArrayList<>();
		if (next.roofs == null) next.roofs = new ArrayList<>();
		if (settings != null && !new java.util.HashSet<>(next.roofs).equals(new java.util.HashSet<>(settings.roofs == null ? List.of() : settings.roofs))) rerender();
		if (!List.of("public", "all", "none").contains(next.hubs)) next.hubs = "public";
		if (next.title == null || next.title.isBlank()) next.title = "Server map";
		next.title = next.title.strip();
		Files.createDirectories(settingsFile.getParent());
		Files.writeString(settingsFile, Json.GSON.toJson(next), StandardCharsets.UTF_8);
		settings = next;
	}

	public void rerender() {
		rerenderAll = true;
	}

	public void setTrimPreview(JsonObject preview) {
		trimPreview = preview;
	}

	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("status", renderStatus);
		out.put("regions", regionsTotal);
		out.put("rendered", regionsRendered);
		out.put("lastPass", lastPass);
		out.put("colors", colorsFromMod ? "from the server (modded blocks included)" : "vanilla colors (modded blocks show grey until the server ran once)");
		out.put("live", overlays != null && server.running());
		return out;
	}

	// ── Overlays and colors from the mod ───────────────────────────────────

	private void pollOverlays() {
		if (!server.running()) {
			fetchedColorsThisRun = false;
			return;
		}
		try {
			JsonObject fresh = JsonParser.parseString(get("/api/map")).getAsJsonObject();
			boolean changedStatic = overlays == null || !same(overlays, fresh, "dimensions") || !same(overlays, fresh, "claims") || !same(overlays, fresh, "landClaims") || !same(overlays, fresh, "hubs");
			overlays = fresh;
			if (changedStatic) {
				Files.createDirectories(mapDir);
				Files.writeString(mapDir.resolve("overlays.json"), fresh.toString(), StandardCharsets.UTF_8);
			}
			if (!fetchedColorsThisRun) {
				String json = get("/api/map/colors");
				Map<String, Integer> fetched = Json.GSON.fromJson(json, new TypeToken<Map<String, Integer>>() { }.getType());
				if (fetched != null && !fetched.isEmpty()) {
					Path file = mapDir.resolve("colors.json");
					String old = Files.exists(file) ? Files.readString(file) : "";
					String now = Json.GSON.toJson(fetched);
					if (!now.equals(old)) {
						Files.writeString(file, now, StandardCharsets.UTF_8);
						colors = fetched;
						colorsFromMod = true;
						// New blocks (mods added or removed): draw everything again with the new colors.
						if (!old.isEmpty()) rerenderAll = true;
					}
				}
				fetchedColorsThisRun = true;
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			// The mod isn't up yet (or is an old version without the map): try again later.
		}
	}

	private static boolean same(JsonObject a, JsonObject b, String key) {
		return String.valueOf(a.get(key)).equals(String.valueOf(b.get(key)));
	}

	private String get(String path) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + modPort + path)).timeout(Duration.ofSeconds(10))
				.header("X-NCC-Secret", secret).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
		return r.body();
	}

	private Map<String, Integer> loadColors() {
		try {
			Path file = mapDir.resolve("colors.json");
			if (Files.exists(file)) {
				Map<String, Integer> m = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Integer>>() { }.getType());
				if (m != null && !m.isEmpty()) {
					colorsFromMod = true;
					return m;
				}
			}
			try (InputStream in = MapService.class.getResourceAsStream("/panel/map/colors-vanilla.json")) {
				if (in != null) return Json.GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), new TypeToken<Map<String, Integer>>() { }.getType());
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Map: no block colors: " + e.getMessage());
		}
		return Map.of();
	}

	// ── Dimensions ─────────────────────────────────────────────────────────

	public record Dim(String key, String id, String name, Path regions, boolean ceiling, JsonObject info) {
	}

	/**
	 * From the mod's last report, or found on disk when the server never ran with the mod. The dungeon dimension
	 * (only generated copies) is left out: no map, no settings.
	 */
	public List<Dim> dimensions() {
		List<String> roofs = settings.roofs == null ? List.of() : settings.roofs;
		return found().stream().filter(d -> !d.id().equals(eu.explorerseden.nicecontrolcenter.Dungeons.DIMENSION)).map(d -> d.ceiling() || !roofs.contains(d.id()) ? d : new Dim(d.key(), d.id(), d.name(), d.regions(), true, d.info())).toList();
	}

	private List<Dim> found() {
		List<Dim> out = new ArrayList<>();
		JsonObject o = overlays;
		if (o != null && o.has("dimensions")) {
			for (JsonElement e : o.getAsJsonArray("dimensions")) {
				JsonObject d = e.getAsJsonObject();
				String id = d.get("id").getAsString();
				out.add(new Dim(key(id), id, d.get("name").getAsString(), serverDir.resolve(d.get("regions").getAsString()),
						d.has("ceiling") && d.get("ceiling").getAsBoolean(), d));
			}
			return out;
		}
		String world = levelName();
		Path dims = serverDir.resolve(world).resolve("dimensions");
		if (Files.isDirectory(dims)) {
			try (Stream<Path> namespaces = Files.list(dims)) {
				for (Path ns : namespaces.toList()) {
					try (Stream<Path> names = Files.list(ns)) {
						for (Path n : names.toList()) {
							String id = ns.getFileName() + ":" + n.getFileName();
							out.add(new Dim(key(id), id, title(n.getFileName().toString()), n.resolve("region"), id.equals("minecraft:the_nether"), null));
						}
					}
				}
			} catch (IOException e) {
				// Nothing found.
			}
		}
		if (out.isEmpty()) {
			out.add(new Dim("minecraft_overworld", "minecraft:overworld", "Overworld", serverDir.resolve(world).resolve("region"), false, null));
			out.add(new Dim("minecraft_the_nether", "minecraft:the_nether", "The Nether", serverDir.resolve(world).resolve("DIM-1/region"), true, null));
			out.add(new Dim("minecraft_the_end", "minecraft:the_end", "The End", serverDir.resolve(world).resolve("DIM1/region"), false, null));
		}
		return out;
	}

	static String key(String id) {
		return id.replace(':', '_').replaceAll("[^a-z0-9_.-]", "_");
	}

	private static String title(String path) {
		String[] words = path.replace('_', ' ').split(" ");
		StringBuilder sb = new StringBuilder();
		for (String w : words) if (!w.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		return sb.toString();
	}

	private String levelName() {
		Properties props = ServerTuning.readProperties(serverDir.resolve("server.properties"));
		return props.getProperty("level-name", "world").strip();
	}

	// ── Rendering ──────────────────────────────────────────────────────────

	/** Bumped when the drawing changes, so existing maps are drawn again once. */
	private static final String RENDERER = "2";

	private boolean rendererChanged() {
		Path marker = mapDir.resolve("renderer");
		try {
			if (Files.exists(marker) && Files.readString(marker).strip().equals(RENDERER)) return false;
			Files.createDirectories(mapDir);
			Files.writeString(marker, RENDERER);
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	private void renderPass() {
		if (!settings.enabled) {
			renderStatus = "Off";
			return;
		}
		try {
			boolean all = rerenderAll || rendererChanged();
			rerenderAll = false;
			MapRenderer renderer = new MapRenderer(colors);
			int total = 0;
			int done = 0;
			for (Dim dim : dimensions()) {
				if (settings.hidden.contains(dim.id()) || !Files.isDirectory(dim.regions())) continue;
				Path tiles = mapDir.resolve("tiles").resolve(dim.key());
				Set<String> changed = new HashSet<>();
				List<Path> files;
				try (Stream<Path> s = Files.list(dim.regions())) {
					files = s.filter(f -> RegionFile.coords(f.getFileName().toString()) != null).toList();
				}
				total += files.size();
				// Chunks redrawn in the bottom row of a region, by region: the top row of the one south of it follows (relief).
				Map<String, Set<Integer>> southEdges = new HashMap<>();
				for (Path mca : files) {
					int[] rc = RegionFile.coords(mca.getFileName().toString());
					Path tile = tiles.resolve("0").resolve(rc[0] + "_" + rc[1] + ".png");
					boolean exists = Files.exists(tile);
					long tileTime = exists ? Files.getLastModifiedTime(tile).toMillis() : 0;
					if (!all && exists && tileTime >= Files.getLastModifiedTime(mca).toMillis()) {
						done++;
						continue;
					}
					renderStatus = "Drawing " + dim.name() + " region " + rc[0] + ", " + rc[1];
					// Only the chunks saved since the tile was drawn (the region file keeps a time per chunk): the
					// game saves the regions around players again and again, mostly with a few changed chunks.
					Set<Integer> chunks = all || !exists ? null : changedChunks(mca, tileTime);
					BufferedImage previous = chunks == null || chunks.isEmpty() || chunks.size() > 512 ? null : readTile(tile);
					BufferedImage image;
					if (chunks != null && chunks.isEmpty()) {
						// Saved, but nothing in it changed: the tile is up to date.
						Files.setLastModifiedTime(tile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
						done++;
						continue;
					} else if (previous != null) {
						image = renderer.update(mca, dim.ceiling(), previous, chunks);
					} else {
						image = renderer.render(mca, dim.ceiling());
						chunks = null;
					}
					if (image != null) MapRenderer.write(image, tile);
					else Files.deleteIfExists(tile);
					changed.add(rc[0] + "_" + rc[1]);
					Set<Integer> edge = new java.util.HashSet<>();
					for (int cx = 0; cx < 32; cx++) if (chunks == null || chunks.contains(31 * 32 + cx)) edge.add(cx);
					if (!edge.isEmpty()) southEdges.put(rc[0] + "_" + (rc[1] + 1), edge);
					done++;
					regionsRendered = done;
				}
				// A changed bottom row also changes the top row of the region south of it (relief shading).
				for (Map.Entry<String, Set<Integer>> e : southEdges.entrySet()) {
					if (changed.contains(e.getKey())) continue;
					String[] p = e.getKey().split("_");
					Path south = dim.regions().resolve("r." + p[0] + "." + p[1] + ".mca");
					Path tile = tiles.resolve("0").resolve(e.getKey() + ".png");
					if (!Files.isRegularFile(south)) continue;
					BufferedImage previous = readTile(tile);
					Set<Integer> top = new java.util.HashSet<>(e.getValue());
					BufferedImage image = previous != null ? renderer.update(south, dim.ceiling(), previous, top) : renderer.render(south, dim.ceiling());
					if (image != null) MapRenderer.write(image, tile);
					changed.add(e.getKey());
				}
				if (!changed.isEmpty()) zoomOut(tiles, changed);
			}
			regionsTotal = total;
			regionsRendered = done;
			lastPass = System.currentTimeMillis();
			renderStatus = "Up to date";
		} catch (IOException | RuntimeException e) {
			renderStatus = "Drawing failed: " + e.getMessage();
		}
	}

	/** The chunks (cz * 32 + cx) of a region saved after the given time; null when the file can't be read. */
	private static Set<Integer> changedChunks(Path mca, long sinceMillis) {
		Set<Integer> out = new HashSet<>();
		// Chunk times are whole seconds; a little slack so a save right around the last drawing isn't missed.
		long since = sinceMillis / 1000 - 2;
		try (RegionFile region = new RegionFile(mca)) {
			for (int cz = 0; cz < 32; cz++) {
				for (int cx = 0; cx < 32; cx++) {
					if (region.has(cx, cz) && Integer.toUnsignedLong(region.timestamp(cx, cz)) >= since) out.add(cz * 32 + cx);
				}
			}
		} catch (IOException e) {
			return null;
		}
		return out;
	}

	private static BufferedImage readTile(Path tile) {
		try {
			BufferedImage img = Files.exists(tile) ? javax.imageio.ImageIO.read(tile.toFile()) : null;
			return img != null && img.getWidth() == MapRenderer.TILE && img.getHeight() == MapRenderer.TILE ? img : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Rebuilds the zoomed-out tiles above the changed ones, level by level. */
	private void zoomOut(Path tiles, Set<String> changed) throws IOException {
		Set<String> current = changed;
		for (int z = -1; z >= MIN_ZOOM; z--) {
			Set<String> parents = new HashSet<>();
			for (String c : current) {
				String[] p = c.split("_");
				parents.add(Math.floorDiv(Integer.parseInt(p[0]), 2) + "_" + Math.floorDiv(Integer.parseInt(p[1]), 2));
			}
			Path below = tiles.resolve(String.valueOf(z + 1));
			for (String parent : parents) {
				String[] p = parent.split("_");
				int px = Integer.parseInt(p[0]);
				int pz = Integer.parseInt(p[1]);
				BufferedImage out = MapRenderer.zoomOut(img(below, 2 * px, 2 * pz), img(below, 2 * px + 1, 2 * pz),
						img(below, 2 * px, 2 * pz + 1), img(below, 2 * px + 1, 2 * pz + 1));
				MapRenderer.write(out, tiles.resolve(String.valueOf(z)).resolve(parent + ".png"));
			}
			current = parents;
		}
	}

	private static BufferedImage img(Path dir, int x, int z) {
		Path f = dir.resolve(x + "_" + z + ".png");
		try {
			return Files.exists(f) ? ImageIO.read(f.toFile()) : null;
		} catch (IOException e) {
			return null;
		}
	}

	// ── What the map page gets ─────────────────────────────────────────────

	/** When the outer wilds are reset next (epoch millis, 0 = not scheduled); set by the panel. */
	private volatile java.util.function.LongSupplier nextReset = () -> 0;

	private volatile java.util.function.BooleanSupplier hasLogo = () -> false;

	void useLogo(java.util.function.BooleanSupplier logo) {
		hasLogo = logo;
	}

	void useResetClock(java.util.function.LongSupplier next) {
		nextReset = next;
	}

	private volatile java.util.function.DoubleSupplier uptime = () -> -1;
	private volatile java.util.function.Supplier<eu.explorerseden.nicecontrolcenter.Maintenance.Settings> maintenance = eu.explorerseden.nicecontrolcenter.Maintenance.Settings::new;
	private volatile java.util.function.LongSupplier nextRestart = () -> 0;

	/** For the status card: uptime over 7 days, maintenance mode, the next scheduled restart. */
	void useStatus(java.util.function.DoubleSupplier uptime, java.util.function.Supplier<eu.explorerseden.nicecontrolcenter.Maintenance.Settings> maintenance,
			java.util.function.LongSupplier nextRestart) {
		this.uptime = uptime;
		this.maintenance = maintenance;
		this.nextRestart = nextRestart;
	}

	private int maxPlayers() {
		try {
			return Integer.parseInt(ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("max-players", "20").trim());
		} catch (RuntimeException e) {
			return 20;
		}
	}

	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("title", settings.title);
		long reset = nextReset.getAsLong();
		if (reset > 0) out.put("nextReset", reset);
		long restart = nextRestart.getAsLong();
		if (restart > 0) out.put("nextRestart", restart);
		out.put("showStatus", settings.showStatus);
		if (hasLogo.getAsBoolean()) out.put("logo", "data/logo");
		Map<String, eu.explorerseden.nicecontrolcenter.Borders.Border> borders = eu.explorerseden.nicecontrolcenter.Borders.read(
				serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Borders.FILE));
		List<Map<String, Object>> dims = new ArrayList<>();
		for (Dim d : dimensions()) {
			if (settings.hidden.contains(d.id())) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", d.key());
			m.put("id", d.id());
			m.put("name", d.name());
			if (d.info() != null) {
				m.put("border", Json.GSON.fromJson(d.info().get("border"), Map.class));
				m.put("spawn", Json.GSON.fromJson(d.info().get("spawn"), Map.class));
			}
			var b = borders.get(d.id());
			if (b != null) m.put("borders", Map.of("shape", b.shape, "x", b.x, "z", b.z, "soft", b.soft, "hard", b.hard));
			dims.add(m);
		}
		out.put("dimensions", dims);
		out.put("minZoom", MIN_ZOOM);
		return out;
	}

	/** Players, claims and hubs as the settings allow. trim: also the world trimmer's preview (admins only). */
	public JsonObject live(boolean trim) {
		JsonObject out = new JsonObject();
		JsonObject o = overlays;
		Settings s = settings;
		out.add("players", s.showPlayers && o != null && server.running() ? visible(o, "players", s) : new JsonArray());
		out.add("claims", s.showClaims && o != null ? visible(o, "claims", s) : new JsonArray());
		out.add("landClaims", s.showClaims && o != null ? visible(o, "landClaims", s) : new JsonArray());
		JsonArray hubs = new JsonArray();
		if (o != null && !s.hubs.equals("none")) {
			for (JsonElement e : visible(o, "hubs", s)) {
				JsonObject h = e.getAsJsonObject();
				if (s.hubs.equals("all") || "public".equalsIgnoreCase(h.has("access") ? h.get("access").getAsString() : "")) hubs.add(h);
			}
		}
		out.add("hubs", hubs);
		out.addProperty("online", server.running());
		if (s.showStatus) {
			JsonObject status = new JsonObject();
			boolean running = server.running();
			status.addProperty("state", !running ? "offline" : maintenance.get().enabled ? "maintenance" : "online");
			JsonArray all = running && o != null && o.has("players") && o.get("players").isJsonArray() ? o.getAsJsonArray("players") : new JsonArray();
			status.addProperty("players", all.size());
			status.addProperty("maxPlayers", maxPlayers());
			if (s.statusNames) {
				JsonArray names = new JsonArray();
				for (JsonElement e : all) if (e.isJsonObject() && e.getAsJsonObject().has("name")) names.add(e.getAsJsonObject().get("name"));
				status.add("names", names);
			}
			if (s.showClock && o != null && o.has("clock") && o.get("clock").isJsonObject()) {
				JsonObject c = o.getAsJsonObject("clock");
				JsonObject clock = new JsonObject();
				for (String k : new String[] { "time", "date", "weekday", "detail", "weather", "day", "minuteOfDay", "twelveHour", "source" }) {
					if (c.has(k)) clock.add(k, c.get(k));
				}
				status.add("clock", clock);
			}
			double up = uptime.getAsDouble();
			if (up >= 0) status.addProperty("uptime7d", up);
			out.add("status", status);
		}
		if (trim && trimPreview != null) out.add("trim", trimPreview);
		return out;
	}

	/** The overlay's entries outside hidden dimensions: a hidden dimension gives away no positions. */
	private static JsonArray visible(JsonObject overlays, String key, Settings s) {
		JsonArray out = new JsonArray();
		if (!overlays.has(key) || !overlays.get(key).isJsonArray()) return out;
		for (JsonElement e : overlays.getAsJsonArray(key)) {
			if (e.isJsonObject() && e.getAsJsonObject().has("dim") && s.hidden.contains(e.getAsJsonObject().get("dim").getAsString())) continue;
			out.add(e);
		}
		return out;
	}

	public Path tile(String dimKey, String zoom, String file) {
		if (!dimKey.matches("[a-z0-9_.-]*[a-z0-9_][a-z0-9_.-]*") || !zoom.matches("-?\\d") || !file.matches("-?\\d+_-?\\d+\\.png")) return null;
		Path f = mapDir.resolve("tiles").resolve(dimKey).resolve(zoom).resolve(file);
		return Files.isRegularFile(f) ? f : null;
	}

	/** Deletes a dimension's tiles so they're drawn again from scratch. */
	public void clearTiles(String dimKey) throws IOException {
		Path tilesDir = mapDir.resolve("tiles").normalize();
		Path dir = tilesDir.resolve(dimKey.toLowerCase(Locale.ROOT)).normalize();
		// "..", "." or "" would be the tiles folder or the map folder itself.
		if (dir.getParent() == null || !dir.getParent().equals(tilesDir)) return;
		if (Files.isDirectory(dir)) ServerFiles.deleteRecursively(dir);
	}
}
