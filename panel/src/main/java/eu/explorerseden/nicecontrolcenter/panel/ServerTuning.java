package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Maintenance;
import eu.explorerseden.nicecontrolcenter.Performance;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Performance settings, maintenance mode and the server list entry (MOTD, icon). The first two are
 * config files the mod reads again when they change, so they apply live; the MOTD and icon apply at the
 * next start (maintenance swaps them live).
 */
final class ServerTuning {
	private final Path serverDir;
	private final Supervisor server;
	private final Audit audit;
	private Notifier events = (type, values) -> { };

	/** The MOTD collection: switched-on ones are picked at random at each start (when rotate is on). */
	static final class Motds {
		public boolean rotate = false;
		public List<Motd> list = new ArrayList<>();
	}

	static final class Motd {
		public String text = "";
		public boolean on = true;
	}

	private final Path motdFile;

	ServerTuning(Path serverDir, Path dataDir, Supervisor server, Audit audit) {
		this.serverDir = serverDir;
		this.motdFile = dataDir.resolve("motds.json");
		this.server = server;
		this.audit = audit;
		server.beforeStart(this::pickMotd);
	}

	Motds motds() {
		try {
			if (Files.exists(motdFile)) {
				Motds m = Json.GSON.fromJson(Files.readString(motdFile), Motds.class);
				if (m != null) {
					if (m.list == null) m.list = new ArrayList<>();
					m.list.removeIf(x -> x == null || x.text == null || x.text.isBlank());
					return m;
				}
			}
		} catch (IOException | RuntimeException e) {
			// Empty.
		}
		return new Motds();
	}

	/** Before a start: a random switched-on MOTD (another one than now, when there's a choice) into server.properties. */
	private void pickMotd(java.util.function.Consumer<String> log) {
		Motds m = motds();
		if (!m.rotate) return;
		List<String> on = m.list.stream().filter(x -> x.on).map(x -> x.text).toList();
		if (on.isEmpty()) return;
		String now = properties().getProperty("motd", "");
		List<String> pick = on.size() > 1 ? on.stream().filter(t -> !t.equals(now)).toList() : on;
		String chosen = pick.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pick.size()));
		try {
			setProperty(serverDir, "motd", chosen);
			log.accept("Message of the day for this start: " + chosen.replaceAll("§.", "").replace('\n', ' '));
		} catch (IOException e) {
			log.accept("Couldn't set the message of the day: " + e.getMessage());
		}
	}

	/** Maintenance on/off goes to Discord (and the stats markers). */
	void useNotifier(Notifier n) {
		events = n;
	}

	Path perfFile() {
		return serverDir.resolve("config").resolve(Performance.FILE);
	}

	Path maintenanceFile() {
		return serverDir.resolve("config").resolve(Maintenance.FILE);
	}

	Maintenance.Settings maintenance() {
		return Maintenance.read(maintenanceFile());
	}

	/** Switches maintenance on or off (the panel's switch and the scheduled step). Returns an error or null. */
	String setMaintenance(boolean on, String by) throws IOException {
		Maintenance.Settings s = maintenance();
		if (s.enabled == on) return null;
		s.enabled = on;
		s.since = on ? System.currentTimeMillis() : 0;
		Maintenance.write(maintenanceFile(), s);
		audit.log(by, "maintenance", on ? "on" : "off", null);
		events.alert("maintenance", Map.of("message", on ? "on" : "off"));
		return null;
	}

	// ── server.properties ──────────────────────────────────────────────

	private Properties properties() {
		return readProperties(serverDir.resolve("server.properties"));
	}

	/** server.properties as Minecraft reads it: UTF-8 (what it writes), or ISO-8859-1 for older files. */
	static Properties readProperties(Path file) {
		Properties p = new Properties();
		try {
			byte[] bytes = Files.readAllBytes(file);
			String text;
			try {
				text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
			} catch (java.nio.charset.CharacterCodingException e) {
				text = new String(bytes, StandardCharsets.ISO_8859_1);
			}
			p.load(new java.io.StringReader(text));
		} catch (IOException e) {
			// Defaults.
		}
		return p;
	}

	/** A value the way server.properties stores it: newlines as \n, anything past ASCII as \\uXXXX. */
	static String escape(String value) {
		StringBuilder b = new StringBuilder();
		for (char c : value.toCharArray()) {
			if (c == '\\') b.append("\\\\");
			else if (c == '\n') b.append("\\n");
			else if (c == '\r') continue;
			else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04X", (int) c));
			else b.append(c);
		}
		return b.toString();
	}

	/** Changes one line of server.properties, keeping the rest (and its comments) as it is. */
	static void setProperty(Path dir, String key, String value) throws IOException {
		Path f = dir.resolve("server.properties");
		List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
		boolean found = false;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(key + "=")) {
				lines.set(i, key + "=" + escape(value));
				found = true;
			}
		}
		if (!found) lines.add(key + "=" + escape(value));
		Files.write(f, lines, StandardCharsets.ISO_8859_1);
	}

	// ── Icons ────────────────────────────────────────────────────────

	/** The upload as a 64×64 PNG (the size Minecraft wants), or an error message in the exception. */
	private static byte[] icon(UploadedFile upload) throws IOException {
		if (upload == null) throw new IOException("Choose an image.");
		if (upload.size() > 5L * 1024 * 1024) throw new IOException("The image can be up to 5 MB.");
		BufferedImage in;
		try (InputStream s = upload.content()) {
			in = ImageIO.read(new ByteArrayInputStream(s.readNBytes(5 * 1024 * 1024 + 1)));
		}
		if (in == null) throw new IOException("Use a PNG, JPEG or GIF image.");
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		// Centered, keeping the aspect ratio.
		double scale = Math.min(64.0 / in.getWidth(), 64.0 / in.getHeight());
		int w = (int) Math.round(in.getWidth() * scale);
		int h = (int) Math.round(in.getHeight() * scale);
		g.drawImage(in, (64 - w) / 2, (64 - h) / 2, w, h, null);
		g.dispose();
		var bytes = new java.io.ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}

	private void writeFile(String name, byte[] bytes) throws IOException {
		Path f = serverDir.resolve(name);
		Path tmp = f.resolveSibling(name + ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Performance (config/nicecontrolcenter-performance.json).
		routes.get("/api/perf/settings", guard(Permissions.DASHBOARD_VIEW, (ctx, me) -> json(ctx, Performance.read(perfFile()))));
		routes.post("/api/perf/settings", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Performance.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Performance.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Performance.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			// Frozen chunks are changed one by one (below), never by a stale form.
			next.frozen = Performance.read(perfFile()).frozen;
			Performance.write(perfFile(), next);
			audit.log(me.name(), "performance.settings", summary(next), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/perf/freeze", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject b = body(ctx);
			String dim = str(b, "dimension");
			int x;
			int z;
			try {
				x = Integer.parseInt(str(b, "x"));
				z = Integer.parseInt(str(b, "z"));
			} catch (NumberFormatException e) {
				error(ctx, HttpStatus.BAD_REQUEST, "Which chunk?");
				return;
			}
			if (!dim.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unknown dimension.");
				return;
			}
			boolean on = Boolean.TRUE.equals(bool(b, "on"));
			Performance.Settings s = Performance.read(perfFile());
			s.frozen.removeIf(c -> c.dimension.equals(dim) && c.x == x && c.z == z);
			if (on) s.frozen.add(new Performance.Chunk(dim, x, z));
			String problem = Performance.validate(s);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Performance.write(perfFile(), s);
			audit.log(me.name(), "performance.freeze", (on ? "froze " : "unfroze ") + dim + " chunk " + x + ", " + z + " (blocks " + x * 16 + ", " + z * 16 + ")", clientIp(ctx));
			json(ctx, Map.of("ok", true, "frozen", s.frozen));
		}));

		// Maintenance (config/nicecontrolcenter-maintenance.json).
		routes.get("/api/maintenance", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", maintenance());
			out.put("players", knownPlayers());
			out.put("icon", Files.isRegularFile(serverDir.resolve(Maintenance.ICON)));
			json(ctx, out);
		}));
		routes.post("/api/maintenance", guard(Permissions.PLAYERS_MANAGE, (ctx, me) -> {
			Maintenance.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Maintenance.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Maintenance.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Maintenance.Settings before = maintenance();
			next.since = next.enabled ? (before.enabled ? before.since : System.currentTimeMillis()) : 0;
			Maintenance.write(maintenanceFile(), next);
			if (before.enabled != next.enabled) {
				audit.log(me.name(), "maintenance", next.enabled ? "on" : "off", clientIp(ctx));
				events.alert("maintenance", Map.of("message", next.enabled ? "on" : "off"));
			} else {
				audit.log(me.name(), "maintenance.settings", "saved", clientIp(ctx));
			}
			json(ctx, Map.of("ok", true, "running", server.running()));
		}));

		// Server list: MOTD and icons.
		routes.get("/api/serverlist", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Properties p = properties();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("motd", p.getProperty("motd", "A Minecraft Server"));
			out.put("motds", motds());
			out.put("maxPlayers", p.getProperty("max-players", "20"));
			out.put("icon", Files.isRegularFile(serverDir.resolve("server-icon.png")));
			out.put("maintenanceIcon", Files.isRegularFile(serverDir.resolve(Maintenance.ICON)));
			out.put("running", server.running());
			json(ctx, out);
		}));
		routes.post("/api/serverlist/motds", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Motds next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Motds.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null || next.list == null || next.list.size() > 100 || next.list.stream().anyMatch(x -> x == null || x.text == null || x.text.length() > 300)) {
				error(ctx, HttpStatus.BAD_REQUEST, "Up to 100 messages of up to 300 characters each.");
				return;
			}
			next.list.removeIf(x -> x.text.isBlank());
			Files.createDirectories(motdFile.getParent());
			Files.writeString(motdFile, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(next));
			audit.log(me.name(), "serverlist.motds", next.list.size() + " messages, " + next.list.stream().filter(x -> x.on).count() + " on, rotate " + (next.rotate ? "on" : "off"), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/serverlist/motd", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			String motd = str(body(ctx), "motd");
			if (motd.length() > 300) {
				error(ctx, HttpStatus.BAD_REQUEST, "The MOTD can be up to 300 characters.");
				return;
			}
			setProperty(serverDir, "motd", motd);
			audit.log(me.name(), "serverlist.motd", motd.replace('\n', ' '), clientIp(ctx));
			json(ctx, Map.of("ok", true, "running", server.running()));
		}));
		for (String which : new String[] { "server-icon.png", Maintenance.ICON }) {
			String key = which.equals(Maintenance.ICON) ? "maintenance" : "normal";
			routes.post("/api/serverlist/icon/" + key, guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
				byte[] png;
				try {
					png = icon(ctx.uploadedFile("icon"));
				} catch (IOException e) {
					error(ctx, HttpStatus.BAD_REQUEST, e.getMessage());
					return;
				}
				writeFile(which, png);
				audit.log(me.name(), "serverlist.icon", which, clientIp(ctx));
				json(ctx, Map.of("ok", true));
			}));
			routes.post("/api/serverlist/icon/" + key + "/remove", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
				Files.deleteIfExists(serverDir.resolve(which));
				audit.log(me.name(), "serverlist.icon", which + " removed", clientIp(ctx));
				json(ctx, Map.of("ok", true));
			}));
			routes.get("/api/serverlist/icon/" + key, guard(Permissions.SERVER_VIEW, (ctx, me) -> {
				Path f = serverDir.resolve(which);
				if (!Files.isRegularFile(f)) {
					ctx.status(HttpStatus.NOT_FOUND).result("No icon");
					return;
				}
				ctx.contentType("image/png").header("Cache-Control", "no-cache").result(Files.readAllBytes(f));
			}));
		}
	}

	/** Entries of a player list file (ops.json, whitelist.json, usercache.json) as JSON objects. */
	private List<com.google.gson.JsonObject> entries(String file) {
		try {
			Path f = serverDir.resolve(file);
			if (!Files.exists(f)) return List.of();
			List<com.google.gson.JsonObject> out = new ArrayList<>();
			for (var e : com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
				if (e.isJsonObject() && e.getAsJsonObject().has("name")) out.add(e.getAsJsonObject());
			}
			return out;
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	private static String uuidOf(com.google.gson.JsonObject o) {
		return o.has("uuid") ? o.get("uuid").getAsString().toLowerCase(java.util.Locale.ROOT) : null;
	}

	/**
	 * Everyone the server knows (operators, the whitelist, who has joined), for the staff checkboxes. One
	 * entry per account, by UUID, under its newest name: usercache.json keeps old names after a rename.
	 */
	private List<Map<String, Object>> knownPlayers() {
		Map<String, String> nameByUuid = new LinkedHashMap<>();
		// usercache: the newest entry per account wins (expiresOn is a month after the last login).
		Map<String, String> newest = new java.util.HashMap<>();
		for (var o : entries("usercache.json")) {
			String id = uuidOf(o);
			if (id == null) continue;
			String expires = o.has("expiresOn") ? o.get("expiresOn").getAsString() : "";
			if (expires.compareTo(newest.getOrDefault(id, "")) >= 0) {
				newest.put(id, expires);
				nameByUuid.put(id, o.get("name").getAsString());
			}
		}
		java.util.Set<String> ops = new java.util.HashSet<>();
		for (String file : new String[] { "ops.json", "whitelist.json" }) {
			for (var o : entries(file)) {
				String id = uuidOf(o);
				String key = id != null ? id : "name:" + o.get("name").getAsString().toLowerCase(java.util.Locale.ROOT);
				nameByUuid.putIfAbsent(key, o.get("name").getAsString());
				if (file.equals("ops.json")) ops.add(key);
			}
		}
		java.util.Map<String, Map<String, Object>> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		nameByUuid.forEach((id, name) -> byName.merge(name, new java.util.HashMap<>(Map.of("name", name, "op", ops.contains(id))),
				(a1, b1) -> { if (Boolean.TRUE.equals(b1.get("op"))) a1.put("op", true); return a1; }));
		for (String n : maintenance().staff) byName.putIfAbsent(n, new java.util.HashMap<>(Map.of("name", n, "op", false)));
		return new ArrayList<>(byName.values());
	}

	private static String summary(Performance.Settings s) {
		List<String> on = new ArrayList<>();
		if (s.dynamic.enabled) on.add("dynamic distances (target " + s.dynamic.targetMspt + " ms)");
		if (s.activation.enabled) on.add("activation range");
		if (s.merge.enabled) on.add("merge radius");
		if (s.breeding.enabled) on.add("breeding caps");
		if (s.villagers.lobotomize) on.add("villager brains");
		return on.isEmpty() ? "all off" : String.join(", ", on);
	}
}
