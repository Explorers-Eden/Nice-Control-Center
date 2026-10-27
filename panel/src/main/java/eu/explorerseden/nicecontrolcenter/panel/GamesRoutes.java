package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Dungeons;
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

	private Path dungeonsFile() {
		return serverDir.resolve("config").resolve(Dungeons.FILE);
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
			// What's only set in game (/game edit: positions, mob spawns, exit pads, kit) stays as the mod wrote it,
			// so a page opened before such an edit doesn't undo it.
			Games.Settings now = Games.read(file());
			for (Games.Arena a : next.arenas) {
				Games.Arena was = now.arenas.stream().filter(x -> x.id.equals(a.id)).findFirst().orElse(null);
				if (was == null) continue;
				a.start = was.start;
				a.top = was.top;
				a.finish = was.finish;
				a.checkpoints = was.checkpoints;
				a.corner = was.corner;
				a.spawn = was.spawn;
				a.areaMin = was.areaMin;
				a.areaMax = was.areaMax;
				a.mobSpawns = was.mobSpawns;
				a.spectate = was.spectate;
				a.spectateRadius = was.spectateRadius;
				a.exitPads = was.exitPads;
				a.kit = was.kit;
			}
			Games.write(file(), next);
			audit.log(me.name(), "games.settings", next.arenas.size() + " arenas", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		// Dungeons: their settings (config file; what /dungeon save found stays as the mod wrote it), runs going on and best clears.
		routes.get("/api/dungeons", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", Dungeons.read(dungeonsFile()));
			JsonElement live = null;
			try {
				live = dashboard.getElement("/api/dungeons");
			} catch (Exception e) {
				live = null;
			}
			if (live != null && live.isJsonObject()) {
				out.put("runs", live.getAsJsonObject().get("runs"));
				out.put("scores", live.getAsJsonObject().get("scores"));
			} else {
				out.put("runs", java.util.List.of());
				out.put("scores", Dungeons.readScores(serverDir.resolve(files.worldFolder()).resolve(Dungeons.SCORES)));
			}
			json(ctx, out);
		}));
		routes.post("/api/dungeons", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Dungeons.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Dungeons.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Dungeons.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			// What the mod found when saving the build is taken from the file, never from the page.
			Dungeons.Settings now = Dungeons.read(dungeonsFile());
			for (Dungeons.Dungeon d : next.dungeons) {
				Dungeons.Dungeon was = now.dungeon(d.id);
				if (was == null) continue;
				d.sourceDim = was.sourceDim;
				d.from = was.from;
				d.to = was.to;
				d.sizeX = was.sizeX;
				d.sizeY = was.sizeY;
				d.sizeZ = was.sizeZ;
				d.pieces = was.pieces;
				d.markers = was.markers;
				d.savedAt = was.savedAt;
				d.savedBy = was.savedBy;
				if (d.kit != null && !d.kit.isEmpty()) d.kit = was.kit;
			}
			next.dungeons.removeIf(d -> now.dungeon(d.id) == null);
			// Removed on the page: their saved blocks go too.
			Dungeons.Settings kept = next;
			for (Dungeons.Dungeon was : now.dungeons) {
				if (kept.dungeon(was.id) != null || !was.name.matches("[A-Za-z0-9_-]{1,32}")) continue;
				Path dir = serverDir.resolve(files.worldFolder()).resolve("nicecontrolcenter").resolve("dungeons").resolve(was.name.toLowerCase(java.util.Locale.ROOT));
				try (var walk = java.nio.file.Files.walk(dir)) {
					for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(f);
				} catch (java.io.IOException e) {
					// Not there or not removable: the folder stays, nothing else depends on it.
				}
			}
			Dungeons.write(dungeonsFile(), next);
			audit.log(me.name(), "dungeons.settings", next.dungeons.size() + " dungeons, " + next.pads.size() + " pads", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/dungeons/close", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			JsonObject answer = dashboard.postForJson("/api/dungeons/close", body, me.name());
			if (answer == null) {
				error(ctx, HttpStatus.CONFLICT, "Runs can be closed while the server runs.");
				return;
			}
			if (answer.has("error")) {
				error(ctx, HttpStatus.BAD_REQUEST, str(answer, "error"));
				return;
			}
			audit.log(me.name(), "dungeons.close", "slot " + str(body, "slot"), clientIp(ctx));
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
