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
		String key = modelKey + "|" + comps.get("minecraft:custom_model_data") + "|" + comps.get("minecraft:dyed_color") + "|" + readyFor;
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

	private record Pick(String model, int tint) {
	}

	private BufferedImage render(String modelKey, JsonObject comps) throws IOException {
		String[] id = split(modelKey);
		JsonObject def = json(id[0], "items/" + id[1] + ".json");
		Pick pick = def != null && def.has("model") ? choose(def.get("model"), comps) : null;
		String model = pick != null ? pick.model() : id[0] + ":item/" + id[1];
		if (model == null) return null;
		return renderModel(model, pick != null ? pick.tint() : -1);
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
				return n.has("base") ? new Pick(n.get("base").getAsString(), -1) : null;
			}
			default -> {
				return choose(n.has("fallback") ? n.get("fallback") : n.get("model"), comps);
			}
		}
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
		JsonObject faces = elements.size() > 0 ? elements.get(0).getAsJsonObject().getAsJsonObject("faces") : null;
		String top = face(faces, textures, "up");
		String left = face(faces, textures, "south", "north");
		String right = face(faces, textures, "east", "west");
		BufferedImage t = top == null ? null : texture(top);
		BufferedImage l = left == null ? null : texture(left);
		BufferedImage r = right == null ? null : texture(right);
		if (t == null && l == null && r == null) return null;
		return cube(t, l, r);
	}

	private static String face(JsonObject faces, Map<String, String> textures, String... names) {
		if (faces == null) return null;
		for (String n : names) {
			if (faces.has(n) && faces.getAsJsonObject(n).has("texture")) {
				String ref = faces.getAsJsonObject(n).get("texture").getAsString();
				return ref.startsWith("#") ? resolve(textures, ref.substring(1)) : ref;
			}
		}
		return null;
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
