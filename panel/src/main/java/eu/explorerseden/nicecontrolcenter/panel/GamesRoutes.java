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

	/** What the file has, without what the page took away (matched by spot). */
	private static java.util.List<Games.Pos> kept(java.util.List<Games.Pos> was, java.util.List<Games.Pos> page) {
		java.util.function.Function<Games.Pos, String> key = (p) -> p.dim + "|" + Math.round(p.x * 2) + "|" + Math.round(p.y * 2) + "|" + Math.round(p.z * 2);
		java.util.Set<String> keep = new java.util.HashSet<>();
		if (page != null) for (Games.Pos p : page) if (p != null) keep.add(key.apply(p));
		return new java.util.ArrayList<>(was.stream().filter(p -> keep.contains(key.apply(p))).toList());
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/games", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Games.Settings s = Games.read(file());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", s);
			// Head hunts: the skin's hash, for the face picture (info/heads/<hash>).
			Map<String, String> skins = new LinkedHashMap<>();
			for (Games.Arena a : s.arenas) {
				String hash = a.type.equals("heads") ? eu.explorerseden.nicecontrolcenter.Pets.skinHash(eu.explorerseden.nicecontrolcenter.Pets.texture(a.headTexture)) : null;
				if (hash != null) skins.put(a.id, hash);
			}
			out.put("headSkins", skins);
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
				a.spectateMin = was.spectateMin;
				a.spectateMax = was.spectateMax;
				// Placed in game; the page can only take some away.
				a.exitPads = kept(was.exitPads, a.exitPads);
				a.entrances = kept(was.entrances, a.entrances);
				a.watchEntrances = kept(was.watchEntrances, a.watchEntrances);
				a.kit = was.kit;
				// Head hunts: heads are hidden in game; the page can only take some out.
				java.util.Set<String> kept = new java.util.HashSet<>();
				for (Games.Pos h : a.heads) kept.add(h.dim + "|" + (int) Math.floor(h.x) + "|" + (int) Math.floor(h.y) + "|" + (int) Math.floor(h.z));
				a.heads = new java.util.ArrayList<>(was.heads.stream().filter(h -> kept.contains(h.dim + "|" + (int) Math.floor(h.x) + "|" + (int) Math.floor(h.y) + "|" + (int) Math.floor(h.z))).toList());
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
				d.connectors = was.connectors;
				d.savedAt = was.savedAt;
				d.savedBy = was.savedBy;
				if (d.kit != null && !d.kit.isEmpty()) d.kit = was.kit;
			}
			next.dungeons.removeIf(d -> now.dungeon(d.id) == null);
			for (Dungeons.Room r : next.rooms) {
				Dungeons.Room was = now.room(r.id);
				if (was == null) continue;
				// Renaming would lose the room's file (named after it): the name stays.
				r.name = was.name;
				r.sourceDim = was.sourceDim;
				r.from = was.from;
				r.to = was.to;
				r.sizeX = was.sizeX;
				r.sizeY = was.sizeY;
				r.sizeZ = was.sizeZ;
				r.markers = was.markers;
				r.connectors = was.connectors;
				r.savedAt = was.savedAt;
				r.savedBy = was.savedBy;
			}
			next.rooms.removeIf(r -> now.room(r.id) == null);
			// Waiting areas are set in game; the page can only take some away.
			java.util.Set<String> keepAreas = next.waiting == null ? java.util.Set.of() : next.waiting.keySet();
			next.waiting = new java.util.LinkedHashMap<>();
			for (var e : now.waiting.entrySet()) if (keepAreas.contains(e.getKey())) next.waiting.put(e.getKey(), e.getValue());
			for (Dungeons.Room was : now.rooms) {
				if (next.room(was.id) != null || !was.name.matches("[A-Za-z0-9_-]{1,32}")) continue;
				try {
					java.nio.file.Files.deleteIfExists(serverDir.resolve(files.worldFolder()).resolve("nicecontrolcenter").resolve("dungeon-rooms").resolve(was.name.toLowerCase(java.util.Locale.ROOT) + ".nbt"));
				} catch (java.io.IOException e) {
					// The file stays; nothing names it any more.
				}
			}
			next.fill();
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
			audit.log(me.name(), "dungeons.settings", next.dungeons.size() + " dungeons, " + next.rooms.size() + " rooms, " + next.pads.size() + " pads", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		// Saves a dungeon or a room again from the build it was made from (the mod reads the world).
		routes.post("/api/dungeons/resave", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			body.addProperty("by", me.name());
			JsonObject answer = dashboard.postForJson("/api/dungeons/resave", body, me.name());
			if (answer == null) {
				error(ctx, HttpStatus.CONFLICT, "Builds can be saved again while the server runs.");
				return;
			}
			if (answer.has("error")) {
				error(ctx, HttpStatus.BAD_REQUEST, str(answer, "error"));
				return;
			}
			audit.log(me.name(), "dungeons.resave", str(body, "kind") + " " + str(body, "name"), clientIp(ctx));
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
