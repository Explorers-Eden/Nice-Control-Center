package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Mini-game arenas: their settings (config file), who plays and the best scores (from the mod, or the world's file when it's off). */
final class GamesRoutes {
	private final Path serverDir;
	private final ServerFiles files;
	private final DashboardProxy dashboard;
	private final Audit audit;

	GamesRoutes(Path serverDir, ServerFiles files, DashboardProxy dashboard, Audit audit) {
		this.serverDir = serverDir;
		this.files = files;
		this.dashboard = dashboard;
		this.audit = audit;
	}

	private Path file() {
		return serverDir.resolve("config").resolve(Games.FILE);
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/games", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Games.Settings s = Games.read(file());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", s);
			// Live (players in each arena now, top 10) while the server runs; else the saved scores.
			JsonElement live = null;
			try {
				live = dashboard.getElement("/api/games");
			} catch (Exception e) {
				live = null;
			}
			if (live != null && live.isJsonArray()) {
				out.put("live", live);
			} else {
				Map<String, Map<String, Games.Score>> scores = Games.readScores(serverDir.resolve(files.worldFolder()).resolve(Games.SCORES));
				out.put("scores", scores);
			}
			json(ctx, out);
		}));
		routes.post("/api/games", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Games.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Games.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Games.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Games.write(file(), next);
			audit.log(me.name(), "games.settings", next.arenas.size() + " arenas", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/games/reset", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			JsonObject answer = dashboard.postForJson("/api/games/reset", body, me.name());
			if (answer == null) {
				error(ctx, HttpStatus.CONFLICT, "Scores can be reset while the server runs.");
				return;
			}
			audit.log(me.name(), "games.reset", str(body, "id"), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
