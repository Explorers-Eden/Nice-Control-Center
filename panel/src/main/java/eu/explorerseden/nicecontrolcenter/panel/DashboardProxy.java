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
			Map.entry("/api/pregen/stop", Permissions.WORLD_TRIM),
			Map.entry("/api/cleanup/run", Permissions.SETTINGS_SERVER),
			Map.entry("/api/loadedchunks/release", Permissions.SETTINGS_SERVER));
	/** Reads that show more than the dashboard view: the console and the chat. */
	private static final Map<String, String> GET_PERMISSIONS = Map.of("/api/console", Permissions.CONSOLE_READ, "/api/chat", Permissions.CONSOLE_READ);
	/** Only the panel itself calls these. */
	private static final Set<String> INTERNAL = Set.of("/api/chat/relay", "/api/chat/ops");
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
	/** parts: the message as tellraw parts, in the Discord settings' format (mods before 1.7.1 use their own). */
	boolean relayChat(String name, String text, com.google.gson.JsonArray parts) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("text", text);
		body.add("parts", parts);
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

	/** A GET to the mod from the panel itself (stats); null when it doesn't answer. */
	JsonObject get(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10)).header("X-NCC-Secret", secret).GET().build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 ? com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * A POST to the mod from the panel itself (schedules). Returns the mod's error, or null when it
	 * answered with ok.
	 */
	String post(String path, JsonObject body) throws InterruptedException {
		return post(path, body, null);
	}

	/** user: the panel account behind it, for "Admin ┃ <name>:" in messages. */
	String post(String path, JsonObject body, String user) throws InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(15)).header("X-NCC-Secret", secret).header("Content-Type", "application/json");
		if (user != null) builder.header("X-NCC-User", user.replaceAll("[^A-Za-z0-9_ .\\-]", "").strip());
		HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject answer = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
			if (answer.has("error") && !answer.get("error").isJsonNull()) return answer.get("error").getAsString();
			return response.statusCode() == 200 ? null : "The mod answered " + response.statusCode() + ".";
		} catch (IOException | RuntimeException e) {
			return "The mod doesn't answer (is the server running with the Nice Control Center mod?).";
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
				.header("X-NCC-Secret", secret)
				// Who is sending, for "Admin ┃ <name>:" in messages to players.
				.header("X-NCC-User", me.name().replaceAll("[^A-Za-z0-9_ .\\-]", "").strip());
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
		Map<String, Object> status = server.status();
		String state = status.get("state").toString();
		if (!page) {
			error(ctx, HttpStatus.SERVICE_UNAVAILABLE, state.equals("RUNNING")
					? "The server runs, but the Nice Control Center mod doesn't answer." : "The dashboard is available while the Minecraft server runs.");
			return;
		}
		String json = eu.explorerseden.nicecontrolcenter.Json.GSON.toJson(status).replace("<", "\\u003c");
		ctx.status(HttpStatus.SERVICE_UNAVAILABLE).contentType("text/html; charset=utf-8").header("Cache-Control", "no-store").result(OFFLINE_PAGE.replace("%STATUS%", json));
	}

	/**
	 * Shown in the panel's frame instead of a mod page while the server is off, starting, stopping or
	 * crashed, or the mod doesn't answer. It follows the server's state and opens the page once it's up.
	 */
	private static final String OFFLINE_PAGE = """
			<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
			<script>try { document.documentElement.dataset.theme = localStorage.getItem('np-theme') || 'dark'; } catch (e) {}</script>
			<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
			<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
			<link rel="stylesheet" href="../app.css"><link rel="stylesheet" href="../panel.css">
			<style>
			html, body { background: transparent; min-height: 0; height: auto; margin: 0; }
			.off { --tone: #8a93a6; position: relative; overflow: hidden; margin: 8px 0; padding: 44px 24px 40px; text-align: center; border-radius: 16px;
				background: radial-gradient(120% 90% at 50% 0%, color-mix(in srgb, var(--tone) 16%, transparent), transparent 70%), var(--card, rgba(255,255,255,.03));
				border: 1px solid color-mix(in srgb, var(--tone) 28%, rgba(var(--ink), .08)); }
			.off::before { content: ''; position: absolute; inset: 0; pointer-events: none; opacity: .5;
				background-image: linear-gradient(rgba(var(--ink), .05) 1px, transparent 1px), linear-gradient(90deg, rgba(var(--ink), .05) 1px, transparent 1px);
				background-size: 24px 24px; mask-image: radial-gradient(70% 80% at 50% 20%, #000, transparent); -webkit-mask-image: radial-gradient(70% 80% at 50% 20%, #000, transparent); }
			.off > * { position: relative; }
			.off.starting, .off.stopping { --tone: #ffb35c; }
			.off.crashed { --tone: #ff6b6b; }
			.off.mod { --tone: #a77bff; }
			.off-icon { width: 76px; height: 76px; margin: 0 auto 18px; display: grid; place-items: center; border-radius: 22px; font-size: 34px; color: var(--tone);
				background: color-mix(in srgb, var(--tone) 14%, transparent); box-shadow: 0 0 0 1px color-mix(in srgb, var(--tone) 30%, transparent), 0 12px 32px -12px var(--tone); }
			.off.starting .off-icon i, .off.stopping .off-icon i { animation: spin 1.4s linear infinite; }
			@keyframes spin { to { transform: rotate(360deg); } }
			.off h2 { margin: 0 0 8px; font-size: var(--fs-xl, 20px); font-weight: 600; color: var(--strong); letter-spacing: -.01em; }
			.off p { margin: 0 auto; max-width: 520px; font-size: var(--fs-md, 14px); line-height: 1.55; color: var(--muted); }
			.off .off-detail { margin-top: 12px; display: inline-block; max-width: 560px; padding: 8px 12px; border-radius: 8px; font-family: var(--mono); font-size: var(--fs-sm, 12px);
				color: var(--text); background: rgba(var(--ink), .06); text-align: left; overflow-wrap: anywhere; }
			.off-bar { width: min(280px, 70%); height: 4px; margin: 20px auto 0; border-radius: 999px; overflow: hidden; background: rgba(var(--ink), .08); }
			.off-bar span { display: block; width: 40%; height: 100%; border-radius: inherit; background: var(--tone); animation: slide 1.3s ease-in-out infinite; }
			@keyframes slide { from { transform: translateX(-110%); } to { transform: translateX(260%); } }
			.off-actions { margin-top: 22px; display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
			.off-note { margin-top: 12px !important; font-size: var(--fs-sm, 12px) !important; color: var(--faint) !important; min-height: 1em; }
			.off-meta { margin-top: 14px !important; font-size: var(--fs-sm, 12px) !important; color: var(--faint) !important; font-variant-numeric: tabular-nums; }
			@media (prefers-reduced-motion: reduce) { .off-icon i, .off-bar span { animation: none !important; } }
			</style></head>
			<body><section class="off" id="off"></section>
			<script>
			let status = %STATUS%;
			const esc = (t) => String(t ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
			const ago = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return s < 60 ? s + ' s' : Math.floor(s / 60) + ' min ' + (s % 60) + ' s'; };
			const go = (hash) => { try { window.top.location.hash = hash; } catch (e) { location.href = '../#' + hash; } };
			function render() {
			  // Just started: the mod's dashboard comes up a few seconds after the server.
			  const fresh = status.state === 'RUNNING' && status.runningSince && Date.now() - status.runningSince < 60000;
			  const s = fresh ? 'LOADING' : status.state;
			  const box = document.getElementById('off');
			  const views = {
			    STOPPED: ['', 'bi-power', 'The server is off', 'This page shows live data from the Minecraft server. Start it to see what\\'s going on.', true],
			    CRASHED: ['crashed', 'bi-exclamation-octagon', 'The server crashed', 'It stopped unexpectedly. The console shows what happened.', true],
			    STARTING: ['starting', 'bi-arrow-repeat', 'The server is starting', 'This page opens on its own as soon as the server is up.', false],
			    STOPPING: ['stopping', 'bi-arrow-repeat', 'The server is stopping', 'Saving the world and shutting down.', false],
			    LOADING: ['starting', 'bi-arrow-repeat', 'Almost there', 'The server is up; the page opens in a moment.', false],
			    RUNNING: ['mod', 'bi-plug', 'The mod doesn\\'t answer', 'The server runs, but the Nice Control Center mod doesn\\'t reply. The panel installs it when \\u201cInstall and update the Nice Control Center mod\\u201d is on (Settings \\u2192 Startup & Java); it needs a restart after installing.', false],
			  };
			  const [cls, icon, title, text, canStart] = views[s] || views.STOPPED;
			  const since = s === 'STARTING' && status.startedAt ? `<p class="off-meta">Starting for ${ago(Date.now() - status.startedAt)}</p>` : '';
			  const restart = s === 'CRASHED' && status.nextRestartAt > Date.now() ? `<p class="off-meta">Restarts on its own in ${ago(status.nextRestartAt - Date.now())}</p>` : '';
			  box.className = 'off ' + cls;
			  box.innerHTML = `<div class="off-icon"><i class="bi ${icon}"></i></div><h2>${title}</h2><p>${text}</p>
			    ${status.message && s !== 'RUNNING' ? `<div class="off-detail">${esc(status.message)}</div>` : ''}
			    ${cls === 'starting' || cls === 'stopping' ? '<div class="off-bar"><span></span></div>' : ''}${since}${restart}
			    <div class="off-actions">${canStart ? '<button type="button" class="np-btn primary" id="start"><i class="bi bi-play-fill"></i> Start the server</button>' : ''}
			      ${s === 'RUNNING' ? '<button type="button" class="np-btn" data-go="java"><i class="bi bi-sliders"></i> Startup settings</button>' : ''}
			      <button type="button" class="np-btn" data-go="server"><i class="bi bi-terminal"></i> Open the console</button></div>
			    <p class="off-note" id="note"></p>`;
			}
			document.addEventListener('click', async (e) => {
			  const g = e.target.closest('[data-go]');
			  if (g) return go(g.dataset.go);
			  const b = e.target.closest('#start');
			  if (!b) return;
			  b.disabled = true;
			  try {
			    const r = await fetch('../api/server/start', { method: 'POST', headers: { 'X-NCC': '1', 'Content-Type': 'application/json' }, body: '{}' });
			    const d = await r.json().catch(() => ({}));
			    if (!r.ok) throw new Error(d.error || (r.status === 403 ? 'You may not start the server.' : 'Could not start the server.'));
			    status = { ...status, state: 'STARTING', startedAt: Date.now(), message: '' };
			    render();
			  } catch (err) {
			    b.disabled = false;
			    document.getElementById('note').textContent = err.message;
			  }
			});
			render();
			setInterval(async () => {
			  try {
			    const r = await fetch('../api/server', { cache: 'no-store' });
			    if (!r.ok) return;
			    const next = await r.json();
			    const was = status.state;
			    status = next;
			    if (next.state === 'RUNNING' && was !== 'RUNNING') { location.reload(); return; }
			    if (next.state !== was || next.state === 'STARTING' || next.state === 'CRASHED') render();
			  } catch (e) { /* the panel is restarting */ }
			}, 2000);
			// Running but the mod doesn't answer yet: look again soon while it just started, later now and then.
			if (status.state === 'RUNNING') setTimeout(() => location.reload(), status.runningSince && Date.now() - status.runningSince < 60000 ? 3000 : 10000);
			</script></body></html>""";

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
