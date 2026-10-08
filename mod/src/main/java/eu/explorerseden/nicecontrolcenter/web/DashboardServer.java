package eu.explorerseden.nicecontrolcenter.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.core.Tracker;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.data.Point;
import eu.explorerseden.nicecontrolcenter.diagnosis.BloatCheck;
import eu.explorerseden.nicecontrolcenter.diagnosis.Comparison;
import eu.explorerseden.nicecontrolcenter.diagnosis.Views;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.log.ErrorWatcher;
import eu.explorerseden.nicecontrolcenter.log.LogCapture;
import eu.explorerseden.nicecontrolcenter.packs.PackSettings;
import eu.explorerseden.nicecontrolcenter.record.Recorder;
import eu.explorerseden.nicecontrolcenter.update.UpdateManager;
import eu.explorerseden.nicecontrolcenter.server.PropertiesEditor;
import eu.explorerseden.nicecontrolcenter.schedule.Scheduler;
import eu.explorerseden.nicecontrolcenter.server.GameRuleEditor;
import eu.explorerseden.nicecontrolcenter.players.PlayerInspector;

/**
 * The live dashboard, served by the mod itself (no external service). Every API call needs the
 * token from the link printed by /ncc web; the page's static files don't.
 */
public final class DashboardServer {
	private static final String WEB = "/assets/nicecontrolcenter/web/";
	private static final String COOKIE = "np_token";
	private static final int PORT_ATTEMPTS = 10;

	private final MinecraftServer minecraft;
	private final ControlCenterConfig config;
	private final long startedAt = System.currentTimeMillis();
	private HttpServer http;
	private ExecutorService executor;
	private String host;
	private int port;

	public DashboardServer(MinecraftServer minecraft, ControlCenterConfig config) {
		this.minecraft = minecraft;
		this.config = config;
	}

	public void start() {
		host = config.bind == null || config.bind.isBlank() || config.bind.equals("auto")
				? (minecraft.isDedicatedServer() ? "0.0.0.0" : "127.0.0.1")
				: config.bind;
		// Under the panel only the panel talks to the dashboard, on the port it chose.
		boolean panel = PanelMode.active();
		if (panel) {
			host = "127.0.0.1";
		}
		int firstPort = panel ? PanelMode.port() : config.port;
		for (int attempt = 0; attempt < (panel ? 1 : PORT_ATTEMPTS); attempt++) {
			int candidate = firstPort + attempt;
			try {
				http = HttpServer.create(new InetSocketAddress(host, candidate), 0);
				port = candidate;
				break;
			} catch (BindException e) {
				NiceControlCenter.LOGGER.warn("Port {} is in use, trying {}", candidate, candidate + 1);
			} catch (IOException e) {
				NiceControlCenter.LOGGER.error("Could not start the Nice Control Center dashboard on {}:{}", host, candidate, e);
				return;
			}
		}
		if (http == null) {
			NiceControlCenter.LOGGER.error("Could not find a free port for the Nice Control Center dashboard (tried {}-{})", config.port,
					config.port + PORT_ATTEMPTS - 1);
			return;
		}
		executor = Executors.newFixedThreadPool(3, r -> {
			Thread thread = new Thread(r, "Nice Control Center Web");
			thread.setDaemon(true);
			return thread;
		});
		http.setExecutor(executor);
		http.createContext("/", this::handle);
		http.start();
		if (panel) {
			NiceControlCenter.LOGGER.info("Nice Control Center dashboard running for the panel on {}:{}", host, port);
		} else {
			NiceControlCenter.LOGGER.info("Nice Control Center dashboard listening on {}:{}. Run /ncc web for the link.", host, port);
		}
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	public boolean running() {
		return http != null;
	}

	public int port() {
		return port;
	}

	/** Link with the token, for chat and console. */
	public String link() {
		if (PanelMode.active()) {
			// The panel has its own logins; the link opens its dashboard tab (links may add #tab).
			String panel = PanelMode.url();
			return (panel.isEmpty() ? "http://" + configuredAddress() + ":8080" : panel) + "/?tab=dashboard";
		}
		String base = config.public_url == null ? "" : config.public_url.trim();
		if (base.isEmpty()) {
			String address = host.equals("0.0.0.0") || host.equals("::") ? configuredAddress() : host;
			base = "http://" + address + ":" + port;
		}
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/?t=" + config.token;
	}

	private String configuredAddress() {
		String ip = minecraft.getLocalIp();
		return ip == null || ip.isBlank() ? "localhost" : ip;
	}

	// ── Routing ─────────────────────────────────────────────────────────────

	private void handle(HttpExchange exchange) throws IOException {
		try {
			route(exchange);
		} catch (ServerThreadException e) {
			NiceControlCenter.LOGGER.warn("Dashboard request failed on the server thread", e);
			try {
				sendText(exchange, 500, "application/json", "{\"error\":\"The server did not respond in time\"}");
			} catch (IOException | RuntimeException ignored) {
				// Headers were already sent.
			}
		} catch (com.google.gson.JsonParseException | IllegalStateException | ClassCastException | NullPointerException
				| UnsupportedOperationException e) {
			// Missing or malformed fields in a request body.
			try {
				sendText(exchange, 400, "application/json", "{\"error\":\"Bad request\"}");
			} catch (IOException | RuntimeException ignored) {
				// Headers were already sent.
			}
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Dashboard request failed", e);
			try {
				sendText(exchange, 500, "application/json", "{\"error\":\"Internal error, see the server log\"}");
			} catch (IOException | RuntimeException ignored) {
				// Headers were already sent.
			}
		} finally {
			exchange.close();
		}
	}

	private void route(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		Map<String, String> query = query(exchange.getRequestURI());
		exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
		exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");

		if (path.equals("/")) {
			String token = query.get("t");
			if (token != null) {
				if (tokenMatches(token)) {
					exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + config.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
				}
				redirect(exchange, "reports".equals(query.get("view")) ? "/?view=reports" : "/");
				return;
			}
			if (!authorized(exchange)) {
				sendText(exchange, 403, "text/html; charset=utf-8", DENIED);
				return;
			}
			sendResource(exchange, "index.html");
			return;
		}
		if (path.equals("/app.css") || path.equals("/app.js") || path.startsWith("/img/")) {
			sendResource(exchange, path.substring(1));
			return;
		}
		if (path.equals("/api/updates/resourcepack/check") && !authorized(exchange)) {
			// Build scripts ping this with their own token, which opens nothing else.
			if (!requirePost(exchange)) {
				return;
			}
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			String secret = config.resource_pack_webhook_token;
			if (secret.isBlank() || auth == null || !auth.startsWith("Bearer ")
					|| !MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
				sendText(exchange, 401, "application/json", "{\"error\":\"Wrong or missing token\"}");
				return;
			}
			UpdateManager.checkPackNow(config);
			NiceControlCenter.LOGGER.info("Resource pack check requested by a build ping");
			sendJson(exchange, Map.of("ok", true));
			return;
		}
		if (!authorized(exchange)) {
			sendText(exchange, 403, "application/json", "{\"error\":\"Open the dashboard with the link from /ncc web\"}");
			return;
		}
		switch (path) {
			case "/api/live" -> sendJson(exchange, live(parseLong(query.get("after"), 0)));
			case "/api/window" -> sendJson(exchange, Views.full(History.window(window(query.get("m")))));
			case "/api/recording" -> sendJson(exchange, recording());
			case "/api/compare" -> {
				try {
					sendJson(exchange, Comparison.compare(query.getOrDefault("a", Comparison.LIVE), query.getOrDefault("b", Comparison.LIVE),
							Recorder.reportsDir(minecraft)));
				} catch (IOException | RuntimeException e) {
					sendJson(exchange, Map.of("error", "Could not compare: " + e.getMessage()));
				}
			}
			case "/api/console" -> {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("enabled", config.web_console);
				result.put("commands", config.web_console && config.web_console_commands);
				result.put("lines", config.web_console ? LogCapture.after(parseLong(query.get("after"), 0)) : List.of());
				sendJson(exchange, result);
			}
			case "/api/console/run" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_console || !config.web_console_commands) {
					sendJson(exchange, Map.of("error", "Running commands from the dashboard is switched off (web_console_commands)."));
					return;
				}
				JsonObject body = jsonBody(exchange);
				String command = body.get("command").getAsString();
				if (command.length() > 32_500 || command.contains("\n")) {
					sendJson(exchange, Map.of("error", "That isn't a single command."));
					return;
				}
				sendJson(exchange, Map.of("output", onServerThread(() -> ConsoleRunner.run(minecraft, command))));
			}
			case "/api/errors" -> {
				boolean sinceReload = "reload".equals(query.get("since"));
				List<ErrorWatcher.Group> all = ErrorWatcher.groups(sinceReload);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("reloadTime", ErrorWatcher.reloadTime());
				result.put("total", all.size());
				result.put("groups", all.size() > 300 ? all.subList(0, 300) : all);
				sendJson(exchange, result);
			}
			case "/api/packsettings" -> {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("editable", config.web_settings_edit);
				result.put("dialogs", onServerThread(() -> PackSettings.list(minecraft)));
				result.put("tree", onServerThread(() -> PackSettings.tree(minecraft)));
				sendJson(exchange, result);
			}
			case "/api/packsettings/apply" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_settings_edit) {
					sendJson(exchange, Map.of("error", "Changing settings from the dashboard is switched off (web_settings_edit)."));
					return;
				}
				Map<String, String> values = new LinkedHashMap<>();
				JsonObject body = jsonBody(exchange);
				body.getAsJsonObject("values").entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
				String dialog = body.get("dialog").getAsString();
				String error = onServerThread(() -> PackSettings.apply(minecraft, dialog, values, "web dashboard"));
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("error", error);
				result.put("dialogs", onServerThread(() -> PackSettings.list(minecraft)));
				sendJson(exchange, result);
			}
			case "/api/updates" -> sendJson(exchange, UpdateManager.summary(config));
			case "/api/updates/check" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if ("off".equals(config.update_mode)) {
					sendJson(exchange, Map.of("error", "Update checks are switched off (update_mode)."));
					return;
				}
				UpdateManager.checkNow(config);
				sendJson(exchange, UpdateManager.summary(config));
			}
			case "/api/updates/action" -> {
				if (!requirePost(exchange)) {
					return;
				}
				JsonObject body = jsonBody(exchange);
				String error = UpdateManager.action(body.get("action").getAsString(), body.get("key").getAsString());
				Map<String, Object> result = UpdateManager.summary(config);
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/updates/rollback" -> {
				if (!requirePost(exchange)) {
					return;
				}
				JsonObject body = jsonBody(exchange);
				String error = UpdateManager.rollback(body.get("backup").getAsString());
				Map<String, Object> result = UpdateManager.summary(config);
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/updates/resourcepack/check" -> {
				if (!requirePost(exchange)) {
					return;
				}
				UpdateManager.checkPackNow(config);
				sendJson(exchange, UpdateManager.summary(config));
			}
			case "/api/updates/resourcepack/source" -> {
				if (!requirePost(exchange)) {
					return;
				}
				JsonObject body = jsonBody(exchange);
				String source = body.get("source").getAsString().trim();
				if (!source.isEmpty() && !source.matches("(https?://\\S+|github:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(@[^\\s#]+)?(#\\S+)?|modrinth:[A-Za-z0-9_-]+)")) {
					sendJson(exchange, Map.of("error", "Use a https:// link, github:owner/repo (optionally @tag and #file name part) or modrinth:project."));
					return;
				}
				config.resource_pack_source = source;
				config.save();
				NiceControlCenter.LOGGER.info("Resource pack source set to \"{}\" from the dashboard", source);
				UpdateManager.checkPackNow(config);
				sendJson(exchange, UpdateManager.summary(config));
			}
			case "/api/gamerules" -> sendJson(exchange, gamerules());
			case "/api/gamerules/save" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_gamerules_edit) {
					sendJson(exchange, Map.of("error", "Changing gamerules from the dashboard is switched off (web_gamerules_edit)."));
					return;
				}
				Map<String, String> changes = new LinkedHashMap<>();
				JsonObject body = jsonBody(exchange);
				body.getAsJsonObject("changes").entrySet().forEach(e -> changes.put(e.getKey(), e.getValue().getAsString()));
				String error = onServerThread(() -> GameRuleEditor.save(minecraft, changes, "web dashboard"));
				Map<String, Object> result = gamerules();
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/players" -> {
				String key = query.get("key");
				PlayerInspector.Live live = onServerThread(() -> PlayerInspector.live(minecraft, config.players_storage, key));
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("actions", config.web_player_actions);
				// The card first: opening it marks the player's answers as read.
				Object detail = key == null ? null : PlayerInspector.detail(minecraft, key, live);
				result.put("players", PlayerInspector.list(minecraft, live));
				result.put("unread", eu.explorerseden.nicecontrolcenter.players.Conversations.unreadTotal());
				if (key != null) {
					result.put("detail", detail);
				}
				sendJson(exchange, result);
			}
			case "/api/players/action" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_player_actions) {
					sendJson(exchange, Map.of("error", "Player actions from the dashboard are switched off (web_player_actions)."));
					return;
				}
				JsonObject body = jsonBody(exchange);
				String uuid = body.get("uuid").getAsString();
				String action = body.get("action").getAsString();
				String text = body.has("text") ? body.get("text").getAsString() : "";
				String error = onServerThread(() -> PlayerInspector.action(minecraft, uuid, action, text));
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/schedule" -> sendJson(exchange, Scheduler.summary(config.web_schedule));
			case "/api/schedule/save", "/api/schedule/delete", "/api/schedule/run", "/api/schedule/enable" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_schedule) {
					sendJson(exchange, Map.of("error", "Scheduled commands from the dashboard are switched off (web_schedule)."));
					return;
				}
				JsonObject body = jsonBody(exchange);
				String error = switch (path) {
					case "/api/schedule/save" -> Scheduler.save(Json.GSON.fromJson(body.get("task"), Scheduler.Task.class), "web dashboard");
					case "/api/schedule/delete" -> Scheduler.delete(body.get("id").getAsString(), "web dashboard");
					case "/api/schedule/enable" -> Scheduler.setEnabled(body.get("id").getAsString(), body.get("on").getAsBoolean());
					default -> Scheduler.runNow(body.get("id").getAsString(), "web dashboard");
				};
				Map<String, Object> result = Scheduler.summary(config.web_schedule);
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/properties" -> sendJson(exchange, properties());
			case "/api/properties/save" -> {
				if (!requirePost(exchange)) {
					return;
				}
				if (!config.web_properties_edit) {
					sendJson(exchange, Map.of("error", "Changing server.properties from the dashboard is switched off (web_properties_edit)."));
					return;
				}
				Map<String, String> changes = new LinkedHashMap<>();
				JsonObject body = jsonBody(exchange);
				body.getAsJsonObject("changes").entrySet().forEach(e -> changes.put(e.getKey(), e.getValue().getAsString()));
				String error = onServerThread(() -> PropertiesEditor.save(minecraft, changes, "web dashboard"));
				Map<String, Object> result = properties();
				result.put("error", error);
				sendJson(exchange, result);
			}
			case "/api/bloat" -> sendJson(exchange, bloatState());
			case "/api/bloat/run" -> {
				if (!requirePost(exchange)) {
					return;
				}
				BloatCheck.start(minecraft);
				sendJson(exchange, bloatState());
			}
			case "/api/monitor" -> {
				if (!requirePost(exchange)) {
					return;
				}
				NiceControlCenter.setMonitoring("1".equals(query.get("on")));
				sendJson(exchange, Map.of("monitoring", Tracker.enabled()));
			}
			case "/api/recording/start" -> {
				if (!requirePost(exchange)) {
					return;
				}
				int minutes = (int) parseLong(query.get("minutes"), 60);
				if (!Tracker.enabled()) {
					NiceControlCenter.setMonitoring(true);
				}
				String error = onServerThread(() -> Recorder.start(minecraft, minutes, "web dashboard", config.max_recording_hours));
				sendJson(exchange, error == null ? recording() : Map.of("error", error));
			}
			case "/api/recording/stop" -> {
				if (!requirePost(exchange)) {
					return;
				}
				onServerThread(() -> {
					Recorder.stop(minecraft);
					return null;
				});
				sendJson(exchange, recording());
			}
			default -> {
				if (path.startsWith("/reports/")) {
					sendReport(exchange, path.substring("/reports/".length()));
				} else {
					sendText(exchange, 404, "text/plain", "Not found");
				}
			}
		}
	}

	private Map<String, Object> live(long after) {
		Map<String, Object> result = new LinkedHashMap<>();
		Point latest = History.latestPoint();
		result.put("now", System.currentTimeMillis());
		result.put("latest", latest);
		result.put("points", History.pointsAfter(after));
		Map<String, Object> server = new LinkedHashMap<>();
		server.put("name", minecraft.getMotd());
		server.put("version", minecraft.getServerVersion());
		server.put("mods", FabricLoader.getInstance().getAllMods().size());
		server.put("dedicated", minecraft.isDedicatedServer());
		server.put("monitorSince", startedAt);
		result.put("server", server);
		result.put("recording", Recorder.status());
		result.put("monitoring", Tracker.enabled());
		result.put("clock", eu.explorerseden.nicecontrolcenter.core.WorldClock.current());
		result.put("lagging", eu.explorerseden.nicecontrolcenter.alert.AlertWatcher.lagging());
		result.put("lagSince", eu.explorerseden.nicecontrolcenter.alert.AlertWatcher.lagSince());
		result.put("replies", eu.explorerseden.nicecontrolcenter.players.Conversations.unreadReplies());
		return result;
	}

	/** The last result plus whether a check is running, so the dashboard can show progress. */
	private static Map<String, Object> bloatState() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("running", BloatCheck.isRunning());
		map.put("scannedFiles", BloatCheck.scannedFiles());
		map.put("error", BloatCheck.lastError());
		map.put("result", BloatCheck.last());
		return map;
	}

	private Map<String, Object> recording() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", Recorder.status());
		result.put("reports", Recorder.reports(minecraft));
		return result;
	}

	private <T> T onServerThread(java.util.function.Supplier<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				future.complete(task.get());
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
		});
		try {
			return future.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new ServerThreadException(e);
		}
	}

	/** A task on the server thread failed or timed out; reported as a 500, never as a bad request. */
	private static final class ServerThreadException extends RuntimeException {
		ServerThreadException(Throwable cause) {
			super("Server did not respond", cause);
		}
	}

	private void sendReport(HttpExchange exchange, String name) throws IOException {
		String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
		if (!decoded.matches("[A-Za-z0-9._-]+\\.html")) {
			sendText(exchange, 400, "text/plain", "Bad report name");
			return;
		}
		Path file = Recorder.reportsDir(minecraft).resolve(decoded);
		if (!Files.isRegularFile(file)) {
			sendText(exchange, 404, "text/plain", "Report not found");
			return;
		}
		if ("1".equals(query(exchange.getRequestURI()).get("download"))) {
			exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + decoded + "\"");
		}
		byte[] bytes = Files.readAllBytes(file);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private boolean authorized(HttpExchange exchange) {
		if (PanelMode.active()) {
			// Only the panel, which checks its own users' permissions first.
			return PanelMode.secretMatches(exchange.getRequestHeaders().getFirst("X-NCC-Secret"));
		}
		String header = exchange.getRequestHeaders().getFirst("X-NP-Token");
		if (header != null && tokenMatches(header)) {
			return true;
		}
		List<String> cookies = exchange.getRequestHeaders().get("Cookie");
		if (cookies == null) {
			return false;
		}
		for (String line : cookies) {
			for (String cookie : line.split(";")) {
				String[] pair = cookie.trim().split("=", 2);
				if (pair.length == 2 && pair[0].equals(COOKIE) && tokenMatches(pair[1])) {
					return true;
				}
			}
		}
		return false;
	}

	private Map<String, Object> gamerules() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("editable", config.web_gamerules_edit);
		result.put("rules", onServerThread(() -> GameRuleEditor.rules(minecraft)));
		return result;
	}

	private Map<String, Object> properties() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("editable", config.web_properties_edit);
		result.put("dedicated", minecraft.isDedicatedServer());
		result.put("groups", PropertiesEditor.groups());
		result.put("fields", onServerThread(() -> PropertiesEditor.fields(minecraft)));
		result.put("pending", UpdateManager.pendingProperties());
		return result;
	}

	private boolean tokenMatches(String token) {
		return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), config.token.getBytes(StandardCharsets.UTF_8));
	}

	/** The request body as JSON, at most 2 MB; anything larger or malformed is answered with 400. */
	private static JsonObject jsonBody(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(2 * 1024 * 1024 + 1);
		if (bytes.length > 2 * 1024 * 1024) {
			throw new com.google.gson.JsonParseException("Request too large");
		}
		return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static boolean requirePost(HttpExchange exchange) throws IOException {
		if (!exchange.getRequestMethod().equals("POST")) {
			sendText(exchange, 405, "text/plain", "Use POST");
			return false;
		}
		return true;
	}

	private static int window(String value) {
		long minutes = parseLong(value, 1);
		return minutes >= 60 ? 60 : minutes >= 15 ? 15 : minutes >= 5 ? 5 : 1;
	}

	private static long parseLong(String value, long fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static Map<String, String> query(URI uri) {
		Map<String, String> result = new HashMap<>();
		String raw = uri.getRawQuery();
		if (raw == null) {
			return result;
		}
		for (String part : raw.split("&")) {
			String[] pair = part.split("=", 2);
			result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
		}
		return result;
	}

	private static void redirect(HttpExchange exchange, String location) throws IOException {
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
	}

	private static void sendJson(HttpExchange exchange, Object body) throws IOException {
		exchange.getResponseHeaders().add("Cache-Control", "no-store");
		byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		if (bytes.length > 2048 && accept != null && accept.contains("gzip")) {
			// The window view is polled every 2 s; compressed it's roughly a tenth of the size.
			java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream(bytes.length / 6);
			try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(packed)) {
				gzip.write(bytes);
			}
			bytes = packed.toByteArray();
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
		}
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sendText(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sendResource(HttpExchange exchange, String name) throws IOException {
		if (name.contains("..")) {
			sendText(exchange, 400, "text/plain", "Bad path");
			return;
		}
		try (InputStream in = DashboardServer.class.getResourceAsStream(WEB + name)) {
			if (in == null) {
				sendText(exchange, 404, "text/plain", "Not found");
				return;
			}
			byte[] bytes = in.readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", contentType(name));
			exchange.getResponseHeaders().add("Cache-Control", "no-cache");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	private static String contentType(String name) {
		if (name.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		if (name.endsWith(".css")) {
			return "text/css; charset=utf-8";
		}
		if (name.endsWith(".js")) {
			return "text/javascript; charset=utf-8";
		}
		if (name.endsWith(".png")) {
			return "image/png";
		}
		if (name.endsWith(".jpg")) {
			return "image/jpeg";
		}
		if (name.endsWith(".ico")) {
			return "image/x-icon";
		}
		return "application/octet-stream";
	}

	private static final String DENIED = """
			<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
			<title>Nice Control Center</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b1018;color:#f7fbff;
			font-family:Montserrat,Arial,sans-serif;padding:16px}main{max-width:480px;text-align:center}h1{font-size:22px}p{color:#cfd7e6;line-height:1.6}
			code{background:rgba(255,255,255,.08);padding:2px 6px;border-radius:6px}</style></head><body><main><h1>Nice Control Center</h1>
			<p>This dashboard needs a personal link. Run <code>/ncc web</code> (or <code>/nicecontrolcenter web</code>) in game or in the server console and open the link it prints.</p>
			</main></body></html>""";
}
