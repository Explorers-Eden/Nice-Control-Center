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

	ServerTuning(Path serverDir, Supervisor server, Audit audit) {
		this.serverDir = serverDir;
		this.server = server;
		this.audit = audit;
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
			out.put("maxPlayers", p.getProperty("max-players", "20"));
			out.put("icon", Files.isRegularFile(serverDir.resolve("server-icon.png")));
			out.put("maintenanceIcon", Files.isRegularFile(serverDir.resolve(Maintenance.ICON)));
			out.put("running", server.running());
			json(ctx, out);
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
