package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** The public map (also on its own host name) and its settings. */
final class MapRoutes {
	private final MapService map;
	private final Auth auth;
	private final Audit audit;
	private final String mapHost;

	MapRoutes(MapService map, Auth auth, Audit audit) {
		this.map = map;
		this.auth = auth;
		this.audit = audit;
		this.mapHost = Panel.env("MAP_HOST", "").toLowerCase();
	}

	/** On the map's own host name only the map exists; register before everything else. */
	void registerHostFilter(JavalinDefaultRoutingApi routes) {
		if (mapHost.isEmpty()) return;
		routes.before(ctx -> {
			String host = ctx.header("Host");
			if (host == null) return;
			host = host.toLowerCase().replaceAll(":\\d+$", "");
			if (!host.equals(mapHost)) return;
			String path = ctx.path();
			if (path.equals("/")) {
				ctx.redirect("/map/");
				ctx.skipRemainingHandlers();
			} else if (!path.startsWith("/map/") && !path.equals("/app.css") && !path.startsWith("/img/")) {
				ctx.status(HttpStatus.NOT_FOUND).result("Not found");
				ctx.skipRemainingHandlers();
			}
		});
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/map/data/info.json", ctx -> {
			if (!enabled(ctx)) return;
			json(ctx, map.info());
		});

		routes.get("/map/data/live.json", ctx -> {
			if (!enabled(ctx)) return;
			Auth.Session session = auth.session(ctx.cookie(Auth.COOKIE));
			boolean trim = session != null && session.can(Permissions.WORLD_TRIM);
			ctx.contentType("application/json").header("Cache-Control", "no-store").result(map.live(trim).toString());
		});

		routes.get("/map/tiles/{dim}/{zoom}/{file}", ctx -> {
			if (!map.settings().enabled) {
				ctx.status(HttpStatus.NOT_FOUND);
				return;
			}
			Path tile = map.tile(ctx.pathParam("dim"), ctx.pathParam("zoom"), ctx.pathParam("file"));
			if (tile == null) {
				ctx.status(HttpStatus.NOT_FOUND);
				return;
			}
			ctx.contentType("image/png").header("Cache-Control", "public, max-age=30");
			ctx.result(Files.readAllBytes(tile));
		});

		routes.get("/api/map", guard(Permissions.MAP_MANAGE, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", map.settings());
			out.put("status", map.status());
			out.put("dimensions", map.dimensions().stream().map(d -> Map.of("id", d.id(), "name", d.name(), "key", d.key())).toList());
			out.put("host", mapHost);
			json(ctx, out);
		}));

		routes.post("/api/map/settings", guard(Permissions.MAP_MANAGE, (ctx, me) -> {
			MapService.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), MapService.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unreadable settings");
				return;
			}
			map.save(next);
			audit.log(me.name(), "map.settings", (next.enabled ? "on" : "off") + ", players " + (next.showPlayers ? "shown" : "hidden")
					+ ", claims " + (next.showClaims ? "shown" : "hidden") + ", hubs " + next.hubs, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/map/rerender", guard(Permissions.MAP_MANAGE, (ctx, me) -> {
			String dim = str(body(ctx), "dim");
			if (!dim.isBlank()) map.clearTiles(MapService.key(dim));
			map.rerender();
			audit.log(me.name(), "map.rerender", dim.isBlank() ? "all" : dim, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}

	private boolean enabled(Context ctx) {
		if (map.settings().enabled) return true;
		error(ctx, HttpStatus.NOT_FOUND, "The map is switched off.");
		return false;
	}
}
