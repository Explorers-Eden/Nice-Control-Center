package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Item icons for the web (the public market): drawn by the panel from the game's own textures and models.
 * The vanilla assets come from the client jar of the server's Minecraft version (downloaded once from
 * Mojang), the server's resource pack (server.properties resource-pack) goes on top, so custom models
 * work: the item_model component, custom_model_data cases and the GUI view of a model. Flat items are
 * drawn exactly; blocks as a small 3D cube; anything else gets a plain tile.
 */
final class ItemIcons {
	private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final int SIZE = 64;
	/** 2: shulker boxes, chests, banners and other items the game draws with code. 3: block models drawn box by box (anvils, stairs, slabs…). */
	private static final int RENDERER = 3;

	private final Path assetsDir;
	private final Path iconsDir;
	private final Path serverDir;
	private final Supplier<String> mcVersion;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Map<String, Boolean> failed = new ConcurrentHashMap<>();
	private volatile String readyFor = "";
	private volatile String problem;

	ItemIcons(Path dataDir, Path serverDir, Supplier<String> mcVersion) {
		this.assetsDir = dataDir.resolve("assets");
		this.iconsDir = dataDir.resolve("icons");
		this.serverDir = serverDir;
		this.mcVersion = mcVersion;
	}

	String problem() {
		return problem;
	}

	Path icon(String hash) {
		if (!hash.matches("[0-9a-f]{40}")) return null;
		Path f = iconsDir.resolve(hash + ".png");
		return Files.isRegularFile(f) ? f : null;
	}

	// ---------------------------------------------------------------- assets

	private Path vanilla(String version) {
		return assetsDir.resolve("vanilla-" + version);
	}

	private Path pack() {
		return assetsDir.resolve("pack");
	}

	/** Downloads what's missing: the vanilla assets for the version and the current resource pack. Call off the request thread. */
	synchronized void prepare() {
		String version = mcVersion.get();
		if (version == null || version.isBlank()) {
			problem = "The server's Minecraft version isn't known yet.";
			return;
		}
		String packUrl = packUrl();
		String key = version + "|" + packUrl;
		if (key.equals(readyFor)) return;
		try {
			Path v = vanilla(version);
			if (!Files.isDirectory(v)) downloadVanilla(version, v);
			Path marker = pack().resolve(".url");
			String had = Files.exists(marker) ? Files.readString(marker) : null;
			if (!packUrl.equals(had == null ? "" : had)) {
				deleteTree(pack());
				if (!packUrl.isEmpty()) {
					Path zip = Files.createTempFile("ncc-pack", ".zip");
					try {
						download(packUrl, zip);
						extract(zip, pack());
					} finally {
						Files.deleteIfExists(zip);
					}
				}
				Files.createDirectories(pack());
				Files.writeString(marker, packUrl);
				deleteTree(iconsDir); // Another pack: draw everything again.
				failed.clear();
			}
			readyFor = key;
			problem = null;
		} catch (IOException | InterruptedException | RuntimeException e) {
			problem = "Couldn't get the textures: " + e.getMessage();
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
		}
	}

	private String packUrl() {
		Properties p = ServerTuning.readProperties(serverDir.resolve("server.properties"));
		String url = p.getProperty("resource-pack", "").strip().replace("\\:", ":");
		return url.startsWith("http") ? url : "";
	}

	private void downloadVanilla(String version, Path into) throws IOException, InterruptedException {
		JsonObject manifest = JsonParser.parseString(get(MANIFEST)).getAsJsonObject();
		String url = null;
		for (JsonElement e : manifest.getAsJsonArray("versions")) {
			if (e.getAsJsonObject().get("id").getAsString().equals(version)) url = e.getAsJsonObject().get("url").getAsString();
		}
		if (url == null) throw new IOException("Minecraft " + version + " isn't in Mojang's list");
		JsonObject meta = JsonParser.parseString(get(url)).getAsJsonObject();
		String client = meta.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
		Path jar = Files.createTempFile("ncc-client", ".jar");
		try {
			download(client, jar);
			Path tmp = into.resolveSibling(into.getFileName() + ".tmp");
			deleteTree(tmp);
			extract(jar, tmp);
			Files.move(tmp, into, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	/** Only what icons need: items/, models/ and the item and block textures. */
	private static void extract(Path zipFile, Path into) throws IOException {
		Files.createDirectories(into);
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			for (ZipEntry e : java.util.Collections.list(zip.entries())) {
				String n = e.getName();
				if (e.isDirectory() || !n.startsWith("assets/") || n.contains("..")) continue;
				String rest = n.substring(n.indexOf('/', 7) + 1);
				if (!(rest.startsWith("items/") || rest.startsWith("models/") || rest.startsWith("textures/item/") || rest.startsWith("textures/block/")
						|| rest.startsWith("textures/"))) continue;
				if (rest.startsWith("textures/") && !n.endsWith(".png")) continue;
				Path out = into.resolve(n.substring("assets/".length())).normalize();
				if (!out.startsWith(into)) continue;
				Files.createDirectories(out.getParent());
				try (InputStream in = zip.getInputStream(e)) {
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private String get(String url) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
		return r.body();
	}

	private void download(String url, Path to) throws IOException, InterruptedException {
		HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(to));
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
	}

	private static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (var walk = Files.walk(p)) {
			for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
		}
	}

	/** assets/&lt;ns&gt;/&lt;rest&gt; from the pack, else vanilla. */
	private Path find(String ns, String rest) {
		Path p = pack().resolve(ns).resolve(rest);
		if (Files.isRegularFile(p)) return p;
		String version = mcVersion.get();
		if (version == null) return null;
		p = vanilla(version).resolve(ns).resolve(rest);
		return Files.isRegularFile(p) ? p : null;
	}

	private JsonObject json(String ns, String rest) {
		Path p = find(ns, rest);
		if (p == null) return null;
		try {
			return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String[] split(String id) {
		int i = id.indexOf(':');
		return i < 0 ? new String[] { "minecraft", id } : new String[] { id.substring(0, i), id.substring(i + 1) };
	}

	// ---------------------------------------------------------------- icons

	/** The icon's name for a stored item (the market's stack JSON); drawn now if needed. Null when it can't be drawn. */
	String iconFor(String stackJson) {
		JsonObject stack;
		try {
			stack = JsonParser.parseString(stackJson).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		String id = stack.has("id") ? stack.get("id").getAsString() : "minecraft:air";
		JsonObject comps = stack.has("components") && stack.get("components").isJsonObject() ? stack.getAsJsonObject("components") : new JsonObject();
		String modelKey = comps.has("minecraft:item_model") ? comps.get("minecraft:item_model").getAsString() : id;
		// RENDERER: raised when icons are drawn differently, so they're drawn again.
		String key = modelKey + "|" + comps.get("minecraft:custom_model_data") + "|" + comps.get("minecraft:dyed_color") + "|" + readyFor + "|" + RENDERER;
		String hash = sha1(key);
		if (Files.isRegularFile(iconsDir.resolve(hash + ".png"))) return hash;
		if (failed.containsKey(hash) || readyFor.isEmpty()) return null;
		try {
			BufferedImage img = render(modelKey, comps);
			if (img == null) {
				failed.put(hash, true);
				return null;
			}
			Files.createDirectories(iconsDir);
			Path tmp = iconsDir.resolve(hash + ".tmp");
			ImageIO.write(img, "png", tmp.toFile());
			Files.move(tmp, iconsDir.resolve(hash + ".png"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return hash;
		} catch (IOException | RuntimeException e) {
			failed.put(hash, true);
			return null;
		}
	}

	private static String sha1(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** special: the game draws it with code (shulker boxes, chests, banners, heads…): {type, texture} of that renderer. */
	private record Pick(String model, int tint, JsonObject special) {
		Pick(String model, int tint) {
			this(model, tint, null);
		}
	}

	private BufferedImage render(String modelKey, JsonObject comps) throws IOException {
		String[] id = split(modelKey);
		JsonObject def = json(id[0], "items/" + id[1] + ".json");
		Pick pick = def != null && def.has("model") ? choose(def.get("model"), comps) : null;
		String model = pick != null ? pick.model() : id[0] + ":item/" + id[1];
		if (model == null) return null;
		if (pick != null && pick.special() != null) {
			BufferedImage img = renderSpecial(pick.special(), model);
			if (img != null) return img;
		}
		return renderModel(model, pick != null ? pick.tint() : -1);
	}

	/**
	 * Items the game draws with code: shulker boxes and chests as cubes cut from their entity textures, the rest
	 * (banners, beds, pots…) as a cube of the base model's particle texture. Null when neither is there.
	 */
	private BufferedImage renderSpecial(JsonObject special, String base) {
		String type = special.has("type") ? special.get("type").getAsString().replace("minecraft:", "") : "";
		String tex = special.has("texture") ? special.get("texture").getAsString() : null;
		if (type.equals("shulker_box") && tex != null) {
			String[] t = split(tex);
			BufferedImage skin = texture(t[0] + ":entity/shulker/" + t[1]);
			if (skin != null && skin.getWidth() >= 64 && skin.getHeight() >= 52) {
				int k = skin.getWidth() / 64;
				// Lid (12 high) over the bottom 4 rows of the base: the front as it looks closed.
				BufferedImage side = new BufferedImage(16 * k, 16 * k, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = side.createGraphics();
				g.drawImage(skin.getSubimage(16 * k, 16 * k, 16 * k, 12 * k), 0, 0, null);
				g.drawImage(skin.getSubimage(16 * k, 48 * k, 16 * k, 4 * k), 0, 12 * k, null);
				g.dispose();
				return cube(skin.getSubimage(16 * k, 0, 16 * k, 16 * k), side, side);
			}
		}
		if (type.equals("chest") && tex != null) {
			String[] t = split(tex);
			BufferedImage skin = texture(t[0] + ":entity/chest/" + t[1]);
			if (skin != null && skin.getWidth() >= 64 && skin.getHeight() >= 43) {
				int k = skin.getWidth() / 64;
				// Lid front (5 high) over the bottom's front (10 high), 14 wide.
				BufferedImage side = new BufferedImage(14 * k, 15 * k, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = side.createGraphics();
				g.drawImage(skin.getSubimage(14 * k, 14 * k, 14 * k, 5 * k), 0, 0, null);
				g.drawImage(skin.getSubimage(14 * k, 33 * k, 14 * k, 10 * k), 0, 5 * k, null);
				g.dispose();
				// The lid's outer face (the other square of the top row is its inside).
				return cube(skin.getSubimage(28 * k, 0, 14 * k, 14 * k), side, side);
			}
		}
		if (type.equals("banner") && special.has("color")) {
			Integer rgb = DYES.get(special.get("color").getAsString());
			if (rgb != null) return banner(rgb);
		}
		// The particle texture of the base model (and its parents).
		String current = base;
		for (int depth = 0; current != null && depth < 16; depth++) {
			String[] id = split(current);
			JsonObject m = json(id[0], "models/" + id[1] + ".json");
			if (m == null) break;
			if (m.has("textures") && m.getAsJsonObject("textures").has("particle")) {
				String ref = m.getAsJsonObject("textures").get("particle").getAsString();
				if (!ref.startsWith("#")) {
					BufferedImage t = texture(ref);
					if (t != null) return cube(t, t, t);
				}
			}
			current = m.has("parent") ? m.get("parent").getAsString() : null;
		}
		return null;
	}

	/** Walks the item definition (select / condition / range_dispatch…) to the model the GUI shows. */
	private Pick choose(JsonElement node, JsonObject comps) {
		if (node == null || !node.isJsonObject()) return null;
		JsonObject n = node.getAsJsonObject();
		String type = n.has("type") ? n.get("type").getAsString().replace("minecraft:", "") : "model";
		String property = n.has("property") ? n.get("property").getAsString().replace("minecraft:", "") : "";
		JsonObject cmd = comps.has("minecraft:custom_model_data") && comps.get("minecraft:custom_model_data").isJsonObject()
				? comps.getAsJsonObject("minecraft:custom_model_data") : new JsonObject();
		int index = n.has("index") ? n.get("index").getAsInt() : 0;
		switch (type) {
			case "model" -> {
				int tint = -1;
				if (n.has("tints") && n.get("tints").isJsonArray() && !n.getAsJsonArray("tints").isEmpty()) {
					JsonObject t = n.getAsJsonArray("tints").get(0).getAsJsonObject();
					String tt = t.has("type") ? t.get("type").getAsString().replace("minecraft:", "") : "";
					if (tt.equals("dye") && comps.has("minecraft:dyed_color")) {
						JsonElement dc = comps.get("minecraft:dyed_color");
						tint = dc.isJsonPrimitive() ? dc.getAsInt() : dc.getAsJsonObject().get("rgb").getAsInt();
					} else if (t.has("default")) tint = color(t.get("default"));
					// Grass and foliage depend on the biome; the inventory shows them in this green.
					else if ("minecraft:grass".equals(type(t))) tint = 0x7CBD6B;
					else if ("minecraft:foliage".equals(type(t)) || "minecraft:dry_foliage".equals(type(t))) tint = 0x48B518;
					else if (t.has("value")) tint = color(t.get("value"));
				}
				return new Pick(n.has("model") ? n.get("model").getAsString() : null, tint);
			}
			case "select" -> {
				String want = null;
				if (property.equals("display_context")) want = "gui";
				else if (property.equals("custom_model_data") && cmd.has("strings") && cmd.getAsJsonArray("strings").size() > index) {
					want = cmd.getAsJsonArray("strings").get(index).getAsString();
				}
				if (want != null && n.has("cases")) {
					for (JsonElement c : n.getAsJsonArray("cases")) {
						JsonElement when = c.getAsJsonObject().get("when");
						boolean hit = when.isJsonArray() ? contains(when.getAsJsonArray(), want) : when.getAsString().equals(want);
						if (hit) return choose(c.getAsJsonObject().get("model"), comps);
					}
				}
				return choose(n.get("fallback"), comps);
			}
			case "condition" -> {
				boolean v = false;
				if (property.equals("custom_model_data") && cmd.has("flags") && cmd.getAsJsonArray("flags").size() > index) {
					v = cmd.getAsJsonArray("flags").get(index).getAsBoolean();
				} else if (property.equals("has_component") && n.has("component")) {
					v = comps.has(n.get("component").getAsString());
				}
				return choose(n.get(v ? "on_true" : "on_false"), comps);
			}
			case "range_dispatch" -> {
				float value = 0;
				if (property.equals("custom_model_data") && cmd.has("floats") && cmd.getAsJsonArray("floats").size() > index) {
					value = cmd.getAsJsonArray("floats").get(index).getAsFloat();
				}
				if (n.has("scale")) value *= n.get("scale").getAsFloat();
				JsonElement best = null;
				float bestAt = Float.NEGATIVE_INFINITY;
				if (n.has("entries")) for (JsonElement e : n.getAsJsonArray("entries")) {
					float th = e.getAsJsonObject().get("threshold").getAsFloat();
					if (th <= value && th >= bestAt) {
						bestAt = th;
						best = e.getAsJsonObject().get("model");
					}
				}
				return choose(best != null ? best : n.get("fallback"), comps);
			}
			case "composite" -> {
				return n.has("models") && !n.getAsJsonArray("models").isEmpty() ? choose(n.getAsJsonArray("models").get(0), comps) : null;
			}
			case "special" -> {
				return n.has("base") ? new Pick(n.get("base").getAsString(), -1, n.has("model") && n.get("model").isJsonObject() ? n.getAsJsonObject("model") : new JsonObject()) : null;
			}
			default -> {
				return choose(n.has("fallback") ? n.get("fallback") : n.get("model"), comps);
			}
		}
	}

	private static String type(JsonObject tint) {
		return tint.has("type") ? tint.get("type").getAsString() : "";
	}

	/** The dye colours as the game tints banners with them. */
	private static final Map<String, Integer> DYES = Map.ofEntries(Map.entry("white", 0xF9FFFE), Map.entry("orange", 0xF9801D), Map.entry("magenta", 0xC74EBD),
			Map.entry("light_blue", 0x3AB3DA), Map.entry("yellow", 0xFED83D), Map.entry("lime", 0x80C71F), Map.entry("pink", 0xF38BAA), Map.entry("gray", 0x474F52),
			Map.entry("light_gray", 0x9D9D97), Map.entry("cyan", 0x169C9C), Map.entry("purple", 0x8932B8), Map.entry("blue", 0x3C44AA), Map.entry("brown", 0x835432),
			Map.entry("green", 0x5E7C16), Map.entry("red", 0xB02E26), Map.entry("black", 0x1D1D21));

	/** A banner: the cloth in its colour hanging from a wooden bar. */
	private static BufferedImage banner(int rgb) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		int u = SIZE / 16;
		g.setColor(new java.awt.Color(0x6B4F2A));
		g.fillRect(3 * u, 1 * u, 10 * u, u);
		g.fillRect(7 * u, 0, 2 * u, 16 * u);
		g.setColor(new java.awt.Color(rgb));
		g.fillRect(4 * u, 2 * u, 8 * u, 13 * u);
		g.setColor(new java.awt.Color(0, 0, 0, 60));
		g.fillRect(4 * u, 2 * u, u, 13 * u);
		g.fillRect(4 * u, 14 * u, 8 * u, u);
		g.dispose();
		return out;
	}

	private static boolean contains(JsonArray a, String s) {
		for (JsonElement e : a) if (e.isJsonPrimitive() && e.getAsString().equals(s)) return true;
		return false;
	}

	private static int color(JsonElement e) {
		if (e.isJsonPrimitive()) return e.getAsInt();
		if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
			JsonArray a = e.getAsJsonArray();
			return (Math.round(a.get(0).getAsFloat() * 255) << 16) | (Math.round(a.get(1).getAsFloat() * 255) << 8) | Math.round(a.get(2).getAsFloat() * 255);
		}
		return -1;
	}

	private BufferedImage renderModel(String model, int tint) throws IOException {
		Map<String, String> textures = new java.util.HashMap<>();
		JsonArray elements = null;
		boolean generated = false;
		String current = model;
		for (int depth = 0; current != null && depth < 16; depth++) {
			String[] id = split(current);
			if (id[1].equals("builtin/generated") || id[1].equals("item/generated")) {
				generated = true;
				if (!id[1].equals("item/generated")) break;
			}
			JsonObject m = json(id[0], "models/" + id[1] + ".json");
			if (m == null) break;
			if (m.has("textures")) for (var e : m.getAsJsonObject("textures").entrySet()) textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
			if (elements == null && m.has("elements")) elements = m.getAsJsonArray("elements");
			current = m.has("parent") ? m.get("parent").getAsString() : null;
		}
		if (generated || elements == null) {
			List<BufferedImage> layers = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				String ref = resolve(textures, "layer" + i);
				if (ref == null) break;
				BufferedImage t = texture(ref);
				if (t != null) layers.add(i == 0 && tint >= 0 ? tinted(t, tint) : t);
			}
			if (layers.isEmpty()) {
				// A model without layers (or a builtin one): try the texture of the same name.
				String[] id = split(model);
				BufferedImage t = texture(id[0] + ":" + id[1]);
				if (t == null) return null;
				layers.add(t);
			}
			return flat(layers);
		}
		return boxes(elements, textures, tint);
	}

	/**
	 * A block model drawn box by box, the way the inventory shows it (from above, south on the left, east on the right):
	 * an anvil, stairs or a slab keep their shape instead of becoming a full cube. Boxes go back to front; each visible
	 * face uses its own texture and UV area. Element rotations are left out (a few degrees don't show at this size).
	 */
	private BufferedImage boxes(JsonArray elements, Map<String, String> textures, int tint) {
		List<JsonObject> list = new ArrayList<>();
		for (JsonElement e : elements) if (e.isJsonObject() && e.getAsJsonObject().has("from") && e.getAsJsonObject().has("to")) list.add(e.getAsJsonObject());
		if (list.isEmpty()) return null;
		// Farther first: the viewer looks from +x, +y, +z.
		list.sort(java.util.Comparator.comparingDouble(e -> {
			double[] a = vec(e, "from"), b = vec(e, "to");
			return (a[0] + b[0]) + (a[2] + b[2]) + (a[1] + b[1]) * 0.5;
		}));
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		boolean drewAny = false;
		for (JsonObject e : list) {
			double[] a = vec(e, "from"), b = vec(e, "to");
			JsonObject faces = e.getAsJsonObject("faces");
			if (faces == null) continue;
			double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
			// South (z = to): x to the right, down from the top.
			drewAny |= face(g, faces, "south", textures, tint, 0.8f, new double[] { a[0], 16 - b[1], b[0], 16 - a[1] }, b[0] - dx, b[1], b[2], dx, 0, 0, 0, -dy, 0);
			// East (x = to): from z = to towards z = from, down from the top.
			drewAny |= face(g, faces, "east", textures, tint, 0.62f, new double[] { 16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1] }, b[0], b[1], b[2], 0, 0, -dz, 0, -dy, 0);
			// Up (y = to): x one way, z the other.
			drewAny |= face(g, faces, "up", textures, tint, 1f, new double[] { a[0], a[2], b[0], b[2] }, a[0], b[1], a[2], dx, 0, 0, 0, 0, dz);
		}
		g.dispose();
		return drewAny ? out : null;
	}

	private static double[] vec(JsonObject e, String key) {
		JsonArray v = e.getAsJsonArray(key);
		return new double[] { v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble() };
	}

	/** Screen position of a model point (0..16 per axis) in the 64 px icon; same projection as {@link #cube}. */
	private static double[] project(double x, double y, double z) {
		double k = 2.0;
		double c = Math.cos(Math.PI / 6);
		double half = 16 * k * c;
		double x0 = (SIZE - 2 * half) / 2;
		return new double[] { x0 + half + k * c * (x - z), k * 0.5 * (x + z) + k * (16 - y) };
	}

	/**
	 * One face: the part of its texture its UV names (default: the part matching the box), turned by the face's
	 * rotation, onto the parallelogram from the origin along u (texture right) and v (texture down) in model space.
	 */
	private boolean face(Graphics2D g, JsonObject faces, String name, Map<String, String> textures, int tint, float shade, double[] defaultUv,
			double ox, double oy, double oz, double ux, double uy, double uz, double vx, double vy, double vz) {
		if (!faces.has(name)) return false;
		JsonObject f = faces.getAsJsonObject(name);
		if (!f.has("texture")) return false;
		String ref = f.get("texture").getAsString();
		String tex = ref.startsWith("#") ? resolve(textures, ref.substring(1)) : ref;
		BufferedImage t = tex == null ? null : texture(tex);
		if (t == null) return false;
		if (tint >= 0 && f.has("tintindex")) t = tinted(t, tint);
		double[] uv = defaultUv;
		if (f.has("uv") && f.getAsJsonArray("uv").size() == 4) {
			JsonArray a = f.getAsJsonArray("uv");
			uv = new double[] { a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble() };
		}
		double s = t.getWidth() / 16.0;
		int x1 = (int) Math.floor(Math.min(uv[0], uv[2]) * s), x2 = (int) Math.ceil(Math.max(uv[0], uv[2]) * s);
		int y1 = (int) Math.floor(Math.min(uv[1], uv[3]) * s), y2 = (int) Math.ceil(Math.max(uv[1], uv[3]) * s);
		x1 = Math.max(0, Math.min(t.getWidth() - 1, x1));
		y1 = Math.max(0, Math.min(t.getHeight() - 1, y1));
		x2 = Math.max(x1 + 1, Math.min(t.getWidth(), x2));
		y2 = Math.max(y1 + 1, Math.min(t.getHeight(), y2));
		BufferedImage part = t.getSubimage(x1, y1, x2 - x1, y2 - y1);
		if (uv[0] > uv[2] || uv[1] > uv[3]) part = flipped(part, uv[0] > uv[2], uv[1] > uv[3]);
		int rotation = f.has("rotation") ? ((f.get("rotation").getAsInt() % 360) + 360) % 360 : 0;
		if (rotation != 0) part = rotated(part, rotation);
		if (shade < 1f) part = shaded(part, shade);
		double[] o = project(ox, oy, oz);
		double[] u = project(ox + ux, oy + uy, oz + uz);
		double[] v = project(ox + vx, oy + vy, oz + vz);
		AffineTransform at = new AffineTransform((u[0] - o[0]) / part.getWidth(), (u[1] - o[1]) / part.getWidth(),
				(v[0] - o[0]) / part.getHeight(), (v[1] - o[1]) / part.getHeight(), o[0], o[1]);
		g.drawImage(part, at, null);
		return true;
	}

	private static BufferedImage flipped(BufferedImage src, boolean horizontal, boolean vertical) {
		int w = src.getWidth(), h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(x, y, src.getRGB(horizontal ? w - 1 - x : x, vertical ? h - 1 - y : y));
		return out;
	}

	/** Turned clockwise by 90, 180 or 270 degrees, like a face's "rotation". */
	private static BufferedImage rotated(BufferedImage src, int degrees) {
		int w = src.getWidth(), h = src.getHeight();
		boolean swap = degrees == 90 || degrees == 270;
		BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src.getRGB(x, y);
				switch (degrees) {
					case 90 -> out.setRGB(h - 1 - y, x, p);
					case 180 -> out.setRGB(w - 1 - x, h - 1 - y, p);
					default -> out.setRGB(y, w - 1 - x, p);
				}
			}
		}
		return out;
	}

	/** "#side" → the texture it points to, through the model's texture map. */
	private static String resolve(Map<String, String> textures, String key) {
		String v = textures.get(key);
		for (int i = 0; v != null && v.startsWith("#") && i < 8; i++) v = textures.get(v.substring(1));
		return v;
	}

	private BufferedImage texture(String ref) {
		String[] id = split(ref);
		Path p = find(id[0], "textures/" + id[1] + ".png");
		if (p == null) return null;
		try {
			BufferedImage img = ImageIO.read(p.toFile());
			if (img == null) return null;
			// Animated textures are frames stacked downwards: take the first.
			if (img.getHeight() > img.getWidth()) img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
			BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
			argb.getGraphics().drawImage(img, 0, 0, null);
			return argb;
		} catch (IOException e) {
			return null;
		}
	}

	private static BufferedImage tinted(BufferedImage src, int rgb) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int tr = (rgb >> 16) & 0xFF, tg = (rgb >> 8) & 0xFF, tb = rgb & 0xFF;
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int p = src.getRGB(x, y);
				int a = p >>> 24;
				int r = ((p >> 16) & 0xFF) * tr / 255, g = ((p >> 8) & 0xFF) * tg / 255, b = (p & 0xFF) * tb / 255;
				out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return out;
	}

	private static BufferedImage flat(List<BufferedImage> layers) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (BufferedImage l : layers) g.drawImage(l, 0, 0, SIZE, SIZE, null);
		g.dispose();
		return out;
	}

	private static BufferedImage shaded(BufferedImage src, float f) {
		if (src == null) return null;
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int p = src.getRGB(x, y);
				int r = Math.round(((p >> 16) & 0xFF) * f), g = Math.round(((p >> 8) & 0xFF) * f), b = Math.round((p & 0xFF) * f);
				out.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (g << 8) | b);
			}
		}
		return out;
	}

	/** A block as a small isometric cube: top, left (darker) and right (darkest) faces. */
	private static BufferedImage cube(BufferedImage top, BufferedImage left, BufferedImage right) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		double k = 2.0; // Screen pixels per texel of a 16 px texture.
		double c = Math.cos(Math.PI / 6);
		double w = 2 * 16 * k * c;
		double x0 = (SIZE - w) / 2;
		draw(g, top, new AffineTransform(k * c, k * 0.5, -k * c, k * 0.5, x0 + w / 2, 0));
		draw(g, shaded(left, 0.8f), new AffineTransform(k * c, k * 0.5, 0, k, x0, 8 * k));
		draw(g, shaded(right, 0.62f), new AffineTransform(k * c, -k * 0.5, 0, k, x0 + w / 2, 16 * k));
		g.dispose();
		return out;
	}

	private static void draw(Graphics2D g, BufferedImage img, AffineTransform t) {
		if (img == null) return;
		AffineTransform scaled = new AffineTransform(t);
		scaled.scale(16.0 / img.getWidth(), 16.0 / img.getHeight());
		g.drawImage(img, scaled, null);
	}
}
