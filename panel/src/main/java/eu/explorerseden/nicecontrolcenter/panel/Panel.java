package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Entry point of the Docker panel: accounts, server control, live console and Java settings. */
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

		Db db = Db.connect();
		Accounts accounts = db == null ? null : new Accounts(db);
		Auth auth = new Auth(dataDir, accounts);
		Audit audit = new Audit(accounts);
		Supervisor server = new Supervisor(serverDir, () -> settings);
		// The mod's dashboard: the mod listens inside the container on this port, only for the panel.
		DashboardProxy dashboard = new DashboardProxy(Integer.parseInt(env("DASHBOARD_PORT", "8765")), server, audit);
		String publicUrl = env("PANEL_PUBLIC_URL", "");
		java.util.List<String> panelFlags = new java.util.ArrayList<>(dashboard.flags(publicUrl));
		// The mod reaches the panel here for Discord login checks and game events.
		panelFlags.add("-Dncc.panel.api=http://127.0.0.1:" + port);
		server.panelFlags(() -> panelFlags, dashboard.secret());
		MapService map = new MapService(serverDir, Path.of(env("MAP_DIR", "/data/map")), dataDir.resolve("map.json"), server,
				Integer.parseInt(env("DASHBOARD_PORT", "8765")), dashboard.secret());
		map.start();
		MapRoutes mapRoutes = new MapRoutes(map, auth, audit);
		Discord discord = new Discord(dataDir.resolve("discord.json"), dataDir.resolve("discord-links.json"), server, audit);
		discord.start();
		ZoneId zone = zone();
		Backups backups = new Backups(serverDir, Path.of(env("BACKUP_DIR", "/data/backups")), dataDir.resolve("backups.json"), server, zone);
		Scheduler scheduler = new Scheduler(dataDir.resolve("schedule.json"), server, backups, audit, zone);
		scheduler.start();
		ServerFiles files = new ServerFiles(serverDir, server);
		LogCleanup cleanup = new LogCleanup(files, dataDir.resolve("cleanup.json"), audit, zone);
		cleanup.start();
		Sftp sftp = new Sftp(files, auth, audit);
		String sftpPort = env("SFTP_PORT", "2022");
		if (!sftpPort.equals("0") && !sftpPort.equalsIgnoreCase("off")) {
			try {
				sftp.start(Integer.parseInt(sftpPort), dataDir.resolve("sftp_host_key"));
			} catch (IOException | RuntimeException e) {
				System.err.println("SFTP could not start on port " + sftpPort + ": " + e.getMessage());
			}
		}
		Companion companion = new Companion();
		Versions versions = new Versions(serverDir, server, backups, companion, audit, () -> settings.serverJar, jar -> {
			settings.serverJar = jar;
			try {
				settings.save(settingsFile);
			} catch (IOException e) {
				System.err.println("Could not save the new server jar: " + e.getMessage());
			}
		}, dataDir.resolve("version-history.json"));
		server.beforeStart(log -> {
			if (settings.companionMod) companion.ensure(serverDir, settings.serverJar, log);
		});
		if (db == null) System.out.println("No DB_URL set: only " + auth.envUser() + " can log in. Set DB_URL, DB_USER and DB_PASS for user accounts.");
		if (accounts != null) {
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "session-cleanup");
				t.setDaemon(true);
				return t;
			}).scheduleAtFixedRate(() -> {
				try {
					if (db.ready()) accounts.deleteExpiredSessions();
				} catch (SQLException e) {
					// Next time.
				}
			}, 1, 60, TimeUnit.MINUTES);
		}

		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			// Config files are edited as JSON bodies; uploads go through multipart and may be large (worlds, mod packs).
			config.http.maxRequestSize = 20L * 1024 * 1024;
			config.jetty.multipartConfig.cacheDirectory(serverDir.resolve(ServerFiles.TMP).toString());
			config.jetty.multipartConfig.maxFileSize(8, io.javalin.config.SizeUnit.GB);
			config.jetty.multipartConfig.maxTotalRequestSize(16, io.javalin.config.SizeUnit.GB);
			config.jetty.multipartConfig.maxInMemoryFileSize(1, io.javalin.config.SizeUnit.MB);
			config.staticFiles.add(web -> {
				web.hostedPath = "/";
				web.directory = "/panel/web";
				web.location = Location.CLASSPATH;
			});
			var routes = config.routes;
			mapRoutes.registerHostFilter(routes);

			// Everything under /api needs a session, except logging in and the health check. Changes also
			// need the X-NCC header, which a cross-site form can't send.
			routes.before("/api/*", ctx -> {
				String path = ctx.path();
				if (path.equals("/api/health") || path.equals("/api/console")) return;
				if (!ctx.method().name().equals("GET") && !"1".equals(ctx.header("X-NCC"))) {
					error(ctx, HttpStatus.FORBIDDEN, "Missing X-NCC header");
					ctx.skipRemainingHandlers();
					return;
				}
				if (path.equals("/api/login")) return;
				Auth.Session session = auth.session(ctx.cookie(Auth.COOKIE));
				if (session == null) {
					error(ctx, HttpStatus.UNAUTHORIZED, "Please log in");
					ctx.skipRemainingHandlers();
					return;
				}
				ctx.attribute(SESSION, session);
			});

			// The dashboard pages and their API calls use the same session; the routes check permissions.
			routes.before("/dashboard*", ctx -> {
				Auth.Session session = auth.session(ctx.cookie(Auth.COOKIE));
				if (session != null) ctx.attribute(SESSION, session);
			});

			routes.get("/api/health", ctx -> json(ctx, Map.of("ok", true, "version", VERSION)));

			routes.post("/api/login", ctx -> {
				JsonObject body = body(ctx);
				String ip = clientIp(ctx);
				String name = str(body, "user");
				Auth.LoginResult result = auth.login(name, str(body, "password"), ip, ctx.header("User-Agent"));
				if (result.token() == null) {
					audit.log(name.isBlank() ? null : name, "login.failed", result.error(), ip);
					error(ctx, result.error().startsWith("Too many") ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED, result.error());
					return;
				}
				audit.log(result.session().name(), "login", null, ip);
				boolean https = "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")) || ctx.scheme().equals("https");
				ctx.header("Set-Cookie", Auth.COOKIE + "=" + result.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800" + (https ? "; Secure" : ""));
				json(ctx, Map.of("ok", true));
			});

			routes.post("/api/logout", guard(null, (ctx, me) -> {
				auth.logout(ctx.cookie(Auth.COOKIE));
				audit.log(me.name(), "logout", null, clientIp(ctx));
				ctx.header("Set-Cookie", Auth.COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
				json(ctx, Map.of("ok", true));
			}));

			routes.get("/api/me", guard(null, (ctx, me) -> {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("user", me.name());
				out.put("envAdmin", me.envAdmin());
				out.put("permissions", me.permissions().contains(Permissions.ALL)
						? Permissions.CATALOG.stream().map(Permissions.Permission::id).toList() : new ArrayList<>(me.permissions()));
				out.put("version", VERSION);
				Map<String, Object> database = new LinkedHashMap<>();
				database.put("configured", db != null);
				database.put("ready", db != null && db.ready());
				database.put("problem", db == null ? null : db.problem());
				out.put("database", database);
				json(ctx, out);
			}));

			routes.get("/api/server", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
				Map<String, Object> out = new LinkedHashMap<>(server.status());
				out.put("eula", server.eulaAccepted());
				json(ctx, out);
			}));
			routes.post("/api/server/start", guard(Permissions.SERVER_POWER, (ctx, me) -> power(ctx, me, audit, "server.start", server.start())));
			routes.post("/api/server/stop", guard(Permissions.SERVER_POWER, (ctx, me) -> power(ctx, me, audit, "server.stop", server.stop())));
			routes.post("/api/server/restart", guard(Permissions.SERVER_POWER, (ctx, me) -> power(ctx, me, audit, "server.restart", server.restart())));
			routes.post("/api/server/kill", guard(Permissions.SERVER_POWER, (ctx, me) -> power(ctx, me, audit, "server.kill", server.kill())));
			routes.post("/api/server/command", guard(Permissions.CONSOLE_WRITE, (ctx, me) -> {
				String command = str(body(ctx), "command");
				String error = server.command(command);
				if (error == null) audit.log(me.name(), "console.command", command.strip(), clientIp(ctx));
				result(ctx, error);
			}));
			routes.post("/api/server/eula", guard(Permissions.SERVER_POWER, (ctx, me) -> {
				server.acceptEula();
				audit.log(me.name(), "server.eula", "accepted the Minecraft EULA", clientIp(ctx));
				json(ctx, Map.of("ok", true));
			}));

			routes.get("/api/settings", guard(Permissions.SERVER_VIEW, (ctx, me) -> json(ctx, settingsView())));
			routes.post("/api/settings", guard(Permissions.SETTINGS_JAVA, (ctx, me) -> {
				PanelSettings next;
				try {
					next = Json.GSON.fromJson(ctx.body(), PanelSettings.class);
				} catch (RuntimeException e) {
					next = null;
				}
				if (next == null) {
					error(ctx, HttpStatus.BAD_REQUEST, "Unreadable settings");
					return;
				}
				String error = next.validate(JvmFlags.runtimes());
				if (error != null) {
					error(ctx, HttpStatus.BAD_REQUEST, error);
					return;
				}
				String changes = diff(settings, next);
				next.save(settingsFile);
				settings = next;
				if (!changes.isEmpty()) audit.log(me.name(), "settings.java", changes, clientIp(ctx));
				json(ctx, settingsView());
			}));

			new AccountRoutes(db, accounts, auth, audit).register(routes);
			dashboard.register(routes);
			new OpsRoutes(backups, scheduler, audit).register(routes);
			new FileRoutes(files, cleanup, audit).register(routes);
			new VersionRoutes(versions).register(routes);
			new DiscordRoutes(discord, dashboard.secret(), audit).register(routes);
			mapRoutes.register(routes);

			// Live console: the last lines first, then each new line. Commands go through POST.
			routes.ws("/api/console", ws -> {
				Map<String, Consumer<String>> listeners = new ConcurrentHashMap<>();
				ws.onConnect(ctx -> {
					Auth.Session session = auth.session(ctx.cookie(Auth.COOKIE));
					if (session == null) {
						ctx.closeSession(4401, "Please log in");
						return;
					}
					if (!session.can(Permissions.CONSOLE_READ)) {
						ctx.closeSession(4403, "Not allowed to read the console");
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
			discord.stop();
			sftp.stop();
			app.stop();
			if (db != null) db.close();
		}, "panel-shutdown"));
		app.start(port);
		System.out.println("Nice Control Center Panel " + VERSION + " listening on port " + port + ", server folder " + serverDir);
		if (settings.autoStart) {
			String error = server.start();
			if (error != null) System.out.println("Not starting the server automatically: " + error);
			else audit.log("panel", "server.start", "automatic start", null);
		}
	}

	private static void power(io.javalin.http.Context ctx, Auth.Session me, Audit audit, String action, String error) {
		if (error == null) audit.log(me.name(), action, null, clientIp(ctx));
		result(ctx, error);
	}

	/** "memoryMaxMb 4096 → 8192, preset aikar → zgc" for the audit log. */
	private static String diff(PanelSettings before, PanelSettings after) {
		List<String> out = new ArrayList<>();
		for (Field f : PanelSettings.class.getFields()) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
			try {
				Object a = f.get(before);
				Object b = f.get(after);
				if (!Objects.equals(a, b)) out.add(f.getName() + " " + a + " → " + b);
			} catch (IllegalAccessException e) {
				// Public fields only.
			}
		}
		return String.join(", ", out);
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

	/** TIMEZONE or TZ (e.g. Europe/Berlin) for schedules and backup names; the system zone otherwise. */
	private static ZoneId zone() {
		String name = env("TIMEZONE", env("TZ", ""));
		try {
			return name.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(name);
		} catch (RuntimeException e) {
			System.err.println("Unknown time zone " + name + ", using " + ZoneId.systemDefault());
			return ZoneId.systemDefault();
		}
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
