package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Entry point of the Docker panel: login, server control, live console and JVM settings. */
public final class Panel {
	public static final String VERSION = readVersion();

	private static volatile PanelSettings settings;

	private Panel() {
	}

	public static void main(String[] args) throws IOException {
		int port = Integer.parseInt(env("PANEL_PORT", "8080"));
		Path serverDir = Path.of(env("SERVER_DIR", "/data/server"));
		Path dataDir = Path.of(env("PANEL_DATA", "/data/panel"));
		Files.createDirectories(serverDir);
		Files.createDirectories(dataDir);
		Path settingsFile = dataDir.resolve("settings.json");
		settings = PanelSettings.load(settingsFile);
		Auth auth = new Auth(dataDir);
		Supplier<PanelSettings> current = () -> settings;
		Supervisor server = new Supervisor(serverDir, current);

		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.staticFiles.add(files -> {
				files.hostedPath = "/";
				files.directory = "/panel/web";
				files.location = Location.CLASSPATH;
			});
			var routes = config.routes;

			// Everything under /api needs a session, except logging in and the health check. Changes also
			// need the X-NCC header, which a cross-site form can't send.
			routes.before("/api/*", ctx -> {
				String path = ctx.path();
				if (path.equals("/api/health") || path.equals("/api/login") || path.equals("/api/console")) return;
				if (auth.session(ctx.cookie(Auth.COOKIE)) == null) {
					json(ctx.status(HttpStatus.UNAUTHORIZED), Map.of("error", "Please log in"));
					ctx.skipRemainingHandlers();
					return;
				}
				if (!ctx.method().name().equals("GET") && !"1".equals(ctx.header("X-NCC"))) {
					json(ctx.status(HttpStatus.FORBIDDEN), Map.of("error", "Missing X-NCC header"));
					ctx.skipRemainingHandlers();
				}
			});

			routes.get("/api/health", ctx -> json(ctx, Map.of("ok", true, "version", VERSION)));

			routes.post("/api/login", ctx -> {
				if (!"1".equals(ctx.header("X-NCC"))) {
					json(ctx.status(HttpStatus.FORBIDDEN), Map.of("error", "Missing X-NCC header"));
					return;
				}
				String ip = clientIp(ctx);
				if (auth.tooManyFails(ip)) {
					json(ctx.status(HttpStatus.TOO_MANY_REQUESTS), Map.of("error", "Too many failed attempts. Try again in a few minutes."));
					return;
				}
				JsonObject body = body(ctx);
				String token = auth.login(str(body, "user"), str(body, "password"), ip);
				if (token == null) {
					json(ctx.status(HttpStatus.UNAUTHORIZED), Map.of("error", "Wrong name or password"));
					return;
				}
				boolean https = "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")) || ctx.scheme().equals("https");
				ctx.header("Set-Cookie", Auth.COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800" + (https ? "; Secure" : ""));
				json(ctx, Map.of("ok", true));
			});

			routes.post("/api/logout", ctx -> {
				auth.logout(ctx.cookie(Auth.COOKIE));
				ctx.header("Set-Cookie", Auth.COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
				json(ctx, Map.of("ok", true));
			});

			routes.get("/api/me", ctx -> json(ctx, Map.of("user", auth.session(ctx.cookie(Auth.COOKIE)).user(), "version", VERSION)));

			routes.get("/api/server", ctx -> {
				Map<String, Object> out = new LinkedHashMap<>(server.status());
				out.put("eula", server.eulaAccepted());
				json(ctx, out);
			});
			routes.post("/api/server/start", ctx -> result(ctx, server.start()));
			routes.post("/api/server/stop", ctx -> result(ctx, server.stop()));
			routes.post("/api/server/restart", ctx -> result(ctx, server.restart()));
			routes.post("/api/server/kill", ctx -> result(ctx, server.kill()));
			routes.post("/api/server/command", ctx -> result(ctx, server.command(str(body(ctx), "command"))));
			routes.post("/api/server/eula", ctx -> {
				server.acceptEula();
				json(ctx, Map.of("ok", true));
			});

			routes.get("/api/settings", ctx -> json(ctx, settingsView()));
			routes.post("/api/settings", ctx -> {
				PanelSettings next;
				try {
					next = Json.GSON.fromJson(ctx.body(), PanelSettings.class);
				} catch (RuntimeException e) {
					next = null;
				}
				if (next == null) {
					json(ctx.status(HttpStatus.BAD_REQUEST), Map.of("error", "Unreadable settings"));
					return;
				}
				String error = next.validate(JvmFlags.runtimes());
				if (error != null) {
					json(ctx.status(HttpStatus.BAD_REQUEST), Map.of("error", error));
					return;
				}
				next.save(settingsFile);
				settings = next;
				json(ctx, settingsView());
			});

			// Live console: the last lines first, then each new line. Commands go through POST.
			routes.ws("/api/console", ws -> {
				Map<String, Consumer<String>> listeners = new java.util.concurrent.ConcurrentHashMap<>();
				ws.onConnect(ctx -> {
					if (auth.session(ctx.cookie(Auth.COOKIE)) == null) {
						ctx.closeSession(4401, "Please log in");
						return;
					}
					ctx.enableAutomaticPings();
					ctx.send(Json.GSON.toJson(Map.of("history", server.history())));
					Consumer<String> listener = line -> send(ctx, line);
					listeners.put(ctx.sessionId(), listener);
					server.listen(listener);
				});
				ws.onClose(ctx -> {
					Consumer<String> listener = listeners.remove(ctx.sessionId());
					if (listener != null) server.unlisten(listener);
				});
				ws.onError(ctx -> {
					Consumer<String> listener = listeners.remove(ctx.sessionId());
					if (listener != null) server.unlisten(listener);
				});
			});
		});

		// `docker stop` sends SIGTERM: save and stop Minecraft first, then the web server.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.shutdown();
			app.stop();
		}, "panel-shutdown"));
		app.start(port);
		System.out.println("Nice Control Center Panel " + VERSION + " listening on port " + port + ", server folder " + serverDir);
		if (settings.autoStart) {
			String error = server.start();
			if (error != null) System.out.println("Not starting the server automatically: " + error);
		}
	}

	private static Map<String, Object> settingsView() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("settings", settings);
		out.put("runtimes", JvmFlags.runtimes());
		out.put("command", String.join(" ", JvmFlags.command(settings)));
		out.put("presets", List.of(
				Map.of("id", "aikar", "name", "Aikar's flags (G1)", "flags", String.join(" ", JvmFlags.AIKAR)),
				Map.of("id", "zgc", "name", "Generational ZGC", "flags", String.join(" ", JvmFlags.ZGC)),
				Map.of("id", "none", "name", "None", "flags", "")));
		return out;
	}

	private static void send(WsContext ctx, String line) {
		if (ctx.session.isOpen()) ctx.send(Json.GSON.toJson(Map.of("line", line)));
	}

	private static void result(Context ctx, String error) {
		if (error == null) json(ctx, Map.of("ok", true));
		else json(ctx.status(HttpStatus.CONFLICT), Map.of("error", error));
	}

	private static void json(Context ctx, Object value) {
		ctx.contentType("application/json").header("Cache-Control", "no-store").result(Json.GSON.toJson(value));
	}

	private static JsonObject body(Context ctx) {
		try {
			return JsonParser.parseString(ctx.body()).getAsJsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	private static String str(JsonObject body, String key) {
		return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString() : "";
	}

	/** Behind a reverse proxy the client's address is in X-Forwarded-For. */
	private static String clientIp(Context ctx) {
		String forwarded = ctx.header("X-Forwarded-For");
		return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].strip() : ctx.ip();
	}

	static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String readVersion() {
		try (InputStream in = Panel.class.getResourceAsStream("/panel/version.txt")) {
			return in == null ? "dev" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			return "dev";
		}
	}
}
