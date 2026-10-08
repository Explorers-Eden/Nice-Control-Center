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
		public String title = "Server map";
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
			boolean changedStatic = overlays == null || !same(overlays, fresh, "dimensions") || !same(overlays, fresh, "claims") || !same(overlays, fresh, "hubs");
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

	/** From the mod's last report, or found on disk when the server never ran with the mod. */
	public List<Dim> dimensions() {
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
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(serverDir.resolve("server.properties"))) {
			props.load(in);
		} catch (IOException e) {
			// Default.
		}
		return props.getProperty("level-name", "world").strip();
	}

	// ── Rendering ──────────────────────────────────────────────────────────

	private void renderPass() {
		if (!settings.enabled) {
			renderStatus = "Off";
			return;
		}
		try {
			boolean all = rerenderAll;
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
				for (Path mca : files) {
					int[] rc = RegionFile.coords(mca.getFileName().toString());
					Path tile = tiles.resolve("0").resolve(rc[0] + "_" + rc[1] + ".png");
					if (!all && Files.exists(tile) && Files.getLastModifiedTime(tile).compareTo(Files.getLastModifiedTime(mca)) >= 0) {
						done++;
						continue;
					}
					renderStatus = "Drawing " + dim.name() + " region " + rc[0] + ", " + rc[1];
					BufferedImage image = renderer.render(mca, dim.ceiling());
					if (image != null) MapRenderer.write(image, tile);
					else Files.deleteIfExists(tile);
					changed.add(rc[0] + "_" + rc[1]);
					done++;
					regionsRendered = done;
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

	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("title", settings.title);
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
		out.add("players", s.showPlayers && o != null && server.running() && o.has("players") ? o.get("players") : new JsonArray());
		out.add("claims", s.showClaims && o != null && o.has("claims") ? o.get("claims") : new JsonArray());
		JsonArray hubs = new JsonArray();
		if (o != null && o.has("hubs") && !s.hubs.equals("none")) {
			for (JsonElement e : o.getAsJsonArray("hubs")) {
				JsonObject h = e.getAsJsonObject();
				if (s.hubs.equals("all") || "public".equalsIgnoreCase(h.has("access") ? h.get("access").getAsString() : "")) hubs.add(h);
			}
		}
		out.add("hubs", hubs);
		out.addProperty("online", server.running());
		if (trim && trimPreview != null) out.add("trim", trimPreview);
		return out;
	}

	public Path tile(String dimKey, String zoom, String file) {
		if (!dimKey.matches("[a-z0-9_.-]+") || !zoom.matches("-?\\d") || !file.matches("-?\\d+_-?\\d+\\.png")) return null;
		Path f = mapDir.resolve("tiles").resolve(dimKey).resolve(zoom).resolve(file);
		return Files.isRegularFile(f) ? f : null;
	}

	/** Deletes a dimension's tiles so they're drawn again from scratch. */
	public void clearTiles(String dimKey) throws IOException {
		Path dir = mapDir.resolve("tiles").resolve(dimKey.toLowerCase(Locale.ROOT));
		if (Files.isDirectory(dir) && dir.startsWith(mapDir.resolve("tiles"))) ServerFiles.deleteRecursively(dir);
	}
}
