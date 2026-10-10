package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Shows the mod's dashboard under /dashboard/. The mod listens on 127.0.0.1 inside the container and
 * only trusts the panel's secret; the panel checks its own user's permissions for every request first
 * and writes every change to the audit log.
 */
final class DashboardProxy {
	/** Changes in the dashboard and the permission each needs. Anything else that changes something needs "*". */
	private static final Map<String, String> POST_PERMISSIONS = Map.ofEntries(
			Map.entry("/api/console/run", Permissions.CONSOLE_WRITE),
			Map.entry("/api/chat/send", Permissions.CONSOLE_WRITE),
			Map.entry("/api/players/action", Permissions.PLAYERS_MANAGE),
			Map.entry("/api/properties/save", Permissions.SETTINGS_SERVER),
			Map.entry("/api/gamerules/save", Permissions.SETTINGS_SERVER),
			Map.entry("/api/packsettings/apply", Permissions.SETTINGS_SERVER),
			Map.entry("/api/updates/check", Permissions.UPDATES_MANAGE),
			Map.entry("/api/updates/action", Permissions.UPDATES_MANAGE),
			Map.entry("/api/updates/rollback", Permissions.UPDATES_MANAGE),
			Map.entry("/api/updates/resourcepack/check", Permissions.UPDATES_MANAGE),
			Map.entry("/api/updates/resourcepack/source", Permissions.UPDATES_MANAGE),
			Map.entry("/api/schedule/save", Permissions.SCHEDULE_MANAGE),
			Map.entry("/api/schedule/delete", Permissions.SCHEDULE_MANAGE),
			Map.entry("/api/schedule/run", Permissions.SCHEDULE_MANAGE),
			Map.entry("/api/schedule/enable", Permissions.SCHEDULE_MANAGE),
			// Harmless tools anyone who can see the dashboard may use.
			Map.entry("/api/recording/start", Permissions.DASHBOARD_VIEW),
			Map.entry("/api/recording/stop", Permissions.DASHBOARD_VIEW),
			Map.entry("/api/bloat/run", Permissions.DASHBOARD_VIEW),
			Map.entry("/api/monitor", Permissions.DASHBOARD_VIEW),
			Map.entry("/api/pregen/start", Permissions.WORLD_TRIM),
			Map.entry("/api/pregen/stop", Permissions.WORLD_TRIM));
	/** Reads that show more than the dashboard view: the console and the chat. */
	private static final Map<String, String> GET_PERMISSIONS = Map.of("/api/console", Permissions.CONSOLE_READ, "/api/chat", Permissions.CONSOLE_READ);
	/** Only the panel itself calls these. */
	private static final Set<String> INTERNAL = Set.of("/api/chat/relay");
	/** Changes that aren't worth an audit entry. */
	private static final Set<String> NOT_AUDITED = Set.of("/api/monitor");

	private final int port;
	private final String secret;
	private final Supervisor server;
	private final Audit audit;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	DashboardProxy(int port, Supervisor server, Audit audit) {
		this.port = port;
		this.server = server;
		this.audit = audit;
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		this.secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Start flags for Minecraft: where the mod listens and the secret it accepts. */
	List<String> flags(String publicUrl) {
		return List.of("-Dncc.panel.port=" + port, "-Dncc.panel.secret=" + secret, "-Dncc.panel.url=" + publicUrl);
	}

	String secret() {
		return secret;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/dashboard/", guard(Permissions.DASHBOARD_VIEW, (ctx, me) -> forward(ctx, me, "/")));
		routes.get("/dashboard/<path>", guard(Permissions.DASHBOARD_VIEW, (ctx, me) -> forward(ctx, me, "/" + ctx.pathParam("path"))));
		routes.post("/dashboard/<path>", guard(Permissions.DASHBOARD_VIEW, (ctx, me) -> {
			String path = "/" + ctx.pathParam("path");
			// The dashboard's own scripts can't send the X-NCC header; a matching Origin shows the request
			// comes from the panel's page and not from another site.
			if (!"1".equals(ctx.header("X-NCC")) && !sameOrigin(ctx)) {
				error(ctx, HttpStatus.FORBIDDEN, "Request from another site");
				return;
			}
			if (INTERNAL.contains(path)) {
				error(ctx, HttpStatus.FORBIDDEN, "Only the panel can do that.");
				return;
			}
			String needed = POST_PERMISSIONS.getOrDefault(path, Permissions.ALL);
			if (!me.can(needed)) {
				error(ctx, HttpStatus.FORBIDDEN, "Your account isn't allowed to do that.");
				return;
			}
			if (forward(ctx, me, path) && !NOT_AUDITED.contains(path)) {
				String body = ctx.body();
				audit.log(me.name(), "dashboard" + path.substring(4).replace('/', '.'), body.length() > 500 ? body.substring(0, 500) + "…" : body,
						clientIp(ctx));
			}
		}));
	}

	/**
	 * Discord → game chat through the mod, so the message is also in the log and the Chat tab.
	 * False when the mod can't take it (not running, or older than 1.4.0).
	 */
	boolean relayChat(String name, String text) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("text", text);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat/relay"))
				.timeout(Duration.ofSeconds(5)).header("X-NCC-Secret", secret).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 && response.body().contains("\"ok\"");
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Returns true when the mod answered with a success status. */
	private boolean forward(Context ctx, Auth.Session me, String path) throws IOException, InterruptedException {
		String needed = ctx.method().name().equals("GET") ? GET_PERMISSIONS.get(path) : null;
		if (needed != null && !me.can(needed)) {
			error(ctx, HttpStatus.FORBIDDEN, "Your account isn't allowed to see that.");
			return false;
		}
		String query = ctx.queryString();
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + (query == null ? "" : "?" + query)))
				.timeout(Duration.ofSeconds(60))
				.header("X-NCC-Secret", secret);
		String type = ctx.header("Content-Type");
		if (type != null) request.header("Content-Type", type);
		if (ctx.method().name().equals("POST")) request.POST(HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes()));
		else request.GET();
		HttpResponse<byte[]> response;
		try {
			response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (ConnectException | HttpTimeoutException e) {
			unavailable(ctx, path);
			return false;
		}
		ctx.status(response.statusCode());
		response.headers().firstValue("Content-Type").ifPresent(ctx::contentType);
		response.headers().firstValue("Content-Disposition").ifPresent(v -> ctx.header("Content-Disposition", v));
		ctx.header("Cache-Control", response.headers().firstValue("Cache-Control").orElse("no-store"));
		ctx.result(response.body());
		return response.statusCode() < 400;
	}

	private void unavailable(Context ctx, String path) {
		boolean page = path.equals("/") || path.endsWith(".html");
		String why = switch (server.status().get("state").toString()) {
			case "RUNNING" -> "The server runs, but the Nice Control Center mod doesn't answer. It needs version 1.1.0 or newer"
					+ " (the panel installs it when \"Install and update the Nice Control Center mod\" is on), and a restart after installing.";
			case "STARTING" -> "The server is starting. The dashboard appears once it's up.";
			default -> "The dashboard is available while the Minecraft server runs.";
		};
		if (!page) {
			error(ctx, HttpStatus.SERVICE_UNAVAILABLE, why);
			return;
		}
		ctx.status(HttpStatus.SERVICE_UNAVAILABLE).contentType("text/html; charset=utf-8").header("Cache-Control", "no-store").result("""
				<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
				<script>try { document.documentElement.dataset.theme = localStorage.getItem('np-theme') === 'light' ? 'light' : 'dark'; } catch (e) {}</script>
				<link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@500;600;700;800&display=swap" rel="stylesheet">
				<link rel="stylesheet" href="../app.css"><link rel="stylesheet" href="../panel.css">
				<style>body { background: transparent; } .pn-offline { margin: 8px 0; }</style>
				<script>setTimeout(() => location.reload(), 5000)</script></head>
				<body><section class="np-card pn-offline"><h3 class="np-card-title">Dashboard not available</h3>
				<p class="np-card-intro">%s</p></section></body></html>""".formatted(why.replace("&", "&amp;").replace("<", "&lt;")));
	}

	private static boolean sameOrigin(Context ctx) {
		String origin = ctx.header("Origin");
		String host = ctx.header("Host");
		if (origin == null || host == null) return false;
		try {
			URI uri = URI.create(origin);
			return (uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")).equalsIgnoreCase(host)
					|| host.equalsIgnoreCase(uri.getHost());
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
