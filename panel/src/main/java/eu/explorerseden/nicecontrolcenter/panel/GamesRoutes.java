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

	/** What the file has, without the spots the page deleted. */
	private static java.util.List<Games.Pos> minus(java.util.List<Games.Pos> was, String owner, String field, java.util.Set<String> gone) {
		return new java.util.ArrayList<>(was.stream().filter(p -> !gone.contains(Removed.spot(owner, field, p))).toList());
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
			// Jukebox songs for the game sound list (written by the mod when the server starts).
			Object songs = java.util.List.of();
			try {
				java.nio.file.Path f = serverDir.resolve("config").resolve(Games.Arena.SONGS);
				if (java.nio.file.Files.exists(f)) songs = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f));
			} catch (Exception e) {
				songs = java.util.List.of();
			}
			out.put("songs", songs);
			// The Nice Actions menu's buttons, for what to leave out in the game worlds (written by the mod when the server starts).
			Object quick = java.util.List.of();
			try {
				java.nio.file.Path f = serverDir.resolve("config").resolve("nicecontrolcenter-quick-actions.json");
				if (java.nio.file.Files.exists(f)) quick = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f));
			} catch (Exception e) {
				quick = java.util.List.of();
			}
			out.put("quickActions", quick);
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
			Removed removed = Removed.from(ctx.body());
			java.util.Set<String> gone = removed.of("points");
			for (Games.Arena a : next.arenas) {
				Games.Arena was = now.arenas.stream().filter(x -> x.id.equals(a.id)).findFirst().orElse(null);
				if (was == null) continue;
				a.start = was.start;
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
				a.spectateSpawns = was.spectateSpawns;
				// Placed in game (also while the page was open): only what the page deleted goes.
				a.exitPads = minus(was.exitPads, a.id, "exitPads", gone);
				a.entrances = minus(was.entrances, a.id, "entrances", gone);
				a.watchEntrances = minus(was.watchEntrances, a.id, "watchEntrances", gone);
				a.heads = minus(was.heads, a.id, "heads", gone);
				a.kit = was.kit;
				a.moreKits = was.moreKits;
				a.galleryLanes = was.galleryLanes;
				a.chefStations = was.chefStations;
			}
			// Arena pool entrances are placed in game: only the ones the page deleted go.
			next.poolPads = new java.util.ArrayList<>(now.poolPads.stream().filter(p -> !gone.contains(Removed.spot("pool:" + p.pool, "poolPads", p.pos))).toList());
			// Games made in game while the page was open stay (only the ones deleted on the page go).
			java.util.Set<String> deleted = removed.of("arenas");
			for (Games.Arena was : now.arenas) {
				if (deleted.contains(was.id) || next.arenas.stream().anyMatch(x -> x.id.equals(was.id))) continue;
				next.arenas.add(was);
			}
			// Renamed games: Rewards rules that named the old name follow.
			Map<String, String> renamed = new LinkedHashMap<>();
			for (Games.Arena a : next.arenas) {
				Games.Arena was = now.arenas.stream().filter(x -> x.id.equals(a.id)).findFirst().orElse(null);
				if (was != null && !was.name.equals(a.name)) renamed.put(was.name.toLowerCase(java.util.Locale.ROOT), a.name);
			}
			Games.write(file(), next);
			if (!renamed.isEmpty()) {
				Path rf = serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Rewards.FILE);
				eu.explorerseden.nicecontrolcenter.Rewards.Settings rs = eu.explorerseden.nicecontrolcenter.Rewards.read(rf);
				int moved = 0;
				for (eu.explorerseden.nicecontrolcenter.Rewards.Rule r : rs.rules) {
					String to = r.value == null ? null : renamed.get(r.value.strip().toLowerCase(java.util.Locale.ROOT));
					if (to != null && java.util.List.of("gameFinished", "gameRecord", "waveCleared", "headsFound", "parkourJumps").contains(r.trigger)) {
						r.value = to;
						moved++;
					}
				}
				if (moved > 0) eu.explorerseden.nicecontrolcenter.Rewards.write(rf, rs);
				audit.log(me.name(), "games.rename", String.join(", ", renamed.entrySet().stream().map(e -> e.getKey() + " → " + e.getValue()).toList()) + (moved > 0 ? " (" + moved + " reward rules follow)" : ""), clientIp(ctx));
			}
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
				// The kits are set in game: kept as the mod wrote them, unless the page cleared them (Clear).
				if (d.kit != null && !d.kit.isEmpty()) {
					d.kit = was.kit;
					d.moreKits = was.moreKits;
				} else {
					d.moreKits = new java.util.ArrayList<>();
				}
			}
			// New ones come from the game (/dungeon save), except rogue runs (no build of their own): the page makes those.
			next.dungeons.removeIf(d -> now.dungeon(d.id) == null && !d.rogue());
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
			// Made in game while the page was open (dungeons, rooms, pads, waiting areas): only what the page deleted goes.
			Removed removed = Removed.from(ctx.body());
			java.util.Set<String> goneDungeons = removed.of("dungeons"), goneRooms = removed.of("rooms"), gonePads = removed.of("pads"), goneWaiting = removed.of("waiting");
			for (Dungeons.Dungeon was : now.dungeons) if (!goneDungeons.contains(was.id) && next.dungeon(was.id) == null) next.dungeons.add(was);
			for (Dungeons.Room was : now.rooms) if (!goneRooms.contains(was.id) && next.room(was.id) == null) next.rooms.add(was);
			if (next.pads == null) next.pads = new java.util.ArrayList<>();
			for (Dungeons.Pad was : now.pads) if (!gonePads.contains(was.id) && next.pads.stream().noneMatch(x -> x.id.equals(was.id))) next.pads.add(was);
			next.waiting = new java.util.LinkedHashMap<>();
			for (var e : now.waiting.entrySet()) if (!goneWaiting.contains(e.getKey())) next.waiting.put(e.getKey(), e.getValue());
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
