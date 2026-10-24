package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.clientIp;
import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.guard;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.Borders;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Land claims in the panel. The world keeps them (the mod's nicecontrolcenter-claims.json in the world
 * folder); this page reads them live from the mod while the server runs and from that file while it's
 * off, and edits them the same way. Every few seconds what changed is copied into the database
 * (tables claims, claim_members, claim_players, view claim_player_summary), for a website to read.
 */
final class ClaimService {
	/** What the mod's /api/claims/changes and the world file both give. */
	static final class Changes {
		String worldId;
		long rev;
		boolean full;
		boolean enabled;
		List<Claims.Claim> claims = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		Map<String, Claims.PlayerBlocks> players = new LinkedHashMap<>();
	}

	private final Path serverDir;
	private final Db db;
	private final Supervisor server;
	private final DashboardProxy dashboard;
	private final Audit audit;
	private final MapService map;
	private volatile long syncedRev = -1;
	private volatile String syncedWorld;
	private volatile long syncedAt;
	private volatile String syncProblem;
	private volatile long fileModified = -1;

	ClaimService(Path serverDir, Db db, Supervisor server, DashboardProxy dashboard, Audit audit, MapService map) {
		this.map = map;
		this.serverDir = serverDir;
		this.db = db;
		this.server = server;
		this.dashboard = dashboard;
		this.audit = audit;
	}

	Path settingsFile() {
		return serverDir.resolve("config").resolve(Claims.FILE);
	}

	Claims.Settings settings() {
		return Claims.read(settingsFile());
	}

	Path worldFile() {
		String name = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("level-name", "world").strip();
		return serverDir.resolve(name.isEmpty() ? "world" : name).resolve(Claims.DATA_FILE);
	}

	private Map<String, Borders.Border> borders() {
		return Borders.read(serverDir.resolve("config").resolve(Borders.FILE));
	}

	void start() {
		if (db == null) return;
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "claim-sync");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(this::sync, 15, 5, TimeUnit.SECONDS);
	}

	// ---------------------------------------------------------------- reading

	/** All claims and players right now: from the mod while the server runs, else from the world file. */
	private Changes live() throws IOException {
		if (server.state() == Supervisor.State.RUNNING) {
			JsonObject answer = dashboard.get("/api/claims/changes?since=0");
			if (answer != null) return Json.GSON.fromJson(answer, Changes.class);
		}
		return fromFile();
	}

	private Changes fromFile() throws IOException {
		Path f = worldFile();
		Claims.Data data = Claims.readData(f);
		Changes c = new Changes();
		c.worldId = Files.exists(f) ? data.worldId : null;
		c.rev = data.rev;
		c.full = true;
		c.enabled = settings().enabled;
		c.claims = new ArrayList<>(data.claims.values());
		c.players = data.players;
		return c;
	}

	// ---------------------------------------------------------------- database copy

	private synchronized void sync() {
		try {
			if (!db.ready()) return;
			if (syncedRev < 0) loadSyncState();
			Supervisor.State state = server.state();
			if (state == Supervisor.State.RUNNING) {
				JsonObject answer = dashboard.get("/api/claims/changes?since=" + Math.max(0, syncedRev));
				if (answer == null) return; // Starting up, or a mod without claims.
				Changes c = Json.GSON.fromJson(answer, Changes.class);
				if (!c.full && !c.worldId.equals(syncedWorld)) {
					answer = dashboard.get("/api/claims/changes?since=0");
					if (answer == null) return;
					c = Json.GSON.fromJson(answer, Changes.class);
				}
				if (c.full || c.rev != syncedRev) write(c);
			} else if (state == Supervisor.State.STOPPED || state == Supervisor.State.CRASHED) {
				Path f = worldFile();
				long modified = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
				if (modified == fileModified || modified == 0) return;
				Changes c = fromFile();
				if (c.rev != syncedRev || !java.util.Objects.equals(c.worldId, syncedWorld)) write(c);
				fileModified = modified;
			}
			syncProblem = null;
		} catch (SQLException | IOException | RuntimeException e) {
			syncProblem = e.getMessage();
			System.err.println("Claim sync: " + e);
		}
	}

	private void loadSyncState() throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement("SELECT world_id, rev, synced_at FROM claim_sync WHERE id = 1");
				ResultSet rs = st.executeQuery()) {
			if (rs.next()) {
				syncedWorld = rs.getString(1);
				syncedRev = rs.getLong(2);
				Timestamp at = rs.getTimestamp(3);
				syncedAt = at == null ? 0 : at.getTime();
			} else {
				syncedRev = 0;
			}
		}
	}

	/** Copies the changes into the database in one transaction. */
	private void write(Changes ch) throws SQLException {
		try (Connection c = db.connection()) {
			c.setAutoCommit(false);
			try {
				if (ch.full) {
					try (PreparedStatement st = c.prepareStatement("DELETE FROM claims")) {
						st.executeUpdate();
					}
				}
				try (PreparedStatement up = c.prepareStatement("""
						INSERT INTO claims (id, parent_id, owner_uuid, owner_name, dimension, min_x, min_z, max_x, max_z, name, color, flags, created_at, updated_at, rev)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
						ON CONFLICT (id) DO UPDATE SET parent_id = EXCLUDED.parent_id, owner_uuid = EXCLUDED.owner_uuid, owner_name = EXCLUDED.owner_name,
						    dimension = EXCLUDED.dimension, min_x = EXCLUDED.min_x, min_z = EXCLUDED.min_z, max_x = EXCLUDED.max_x, max_z = EXCLUDED.max_z,
						    name = EXCLUDED.name, color = EXCLUDED.color, flags = EXCLUDED.flags, updated_at = EXCLUDED.updated_at, rev = EXCLUDED.rev""");
						PreparedStatement clear = c.prepareStatement("DELETE FROM claim_members WHERE claim_id = ?");
						PreparedStatement member = c.prepareStatement("INSERT INTO claim_members (claim_id, player_uuid, player_name, role) VALUES (?, ?, ?, ?)")) {
					for (Claims.Claim cl : ch.claims) {
						UUID id = UUID.fromString(cl.id);
						up.setObject(1, id);
						up.setObject(2, cl.parent == null ? null : UUID.fromString(cl.parent), Types.OTHER);
						up.setObject(3, cl.owner == null ? null : UUID.fromString(cl.owner), Types.OTHER);
						up.setString(4, cl.ownerName);
						up.setString(5, cl.dim);
						up.setInt(6, cl.minX);
						up.setInt(7, cl.minZ);
						up.setInt(8, cl.maxX);
						up.setInt(9, cl.maxZ);
						up.setString(10, cl.name == null ? "" : cl.name);
						up.setString(11, Claims.hex(cl.color));
						up.setString(12, Json.GSON.toJson(cl.flags));
						up.setTimestamp(13, new Timestamp(cl.created));
						up.setTimestamp(14, new Timestamp(cl.updated));
						up.setLong(15, cl.rev);
						up.addBatch();
						clear.setObject(1, id);
						clear.addBatch();
						if (cl.members != null) for (Map.Entry<String, Claims.Member> m : cl.members.entrySet()) {
							member.setObject(1, id);
							member.setObject(2, UUID.fromString(m.getKey()));
							member.setString(3, m.getValue().name);
							member.setString(4, m.getValue().role.name());
							member.addBatch();
						}
					}
					up.executeBatch();
					clear.executeBatch();
					member.executeBatch();
				}
				if (ch.removed != null && !ch.removed.isEmpty()) {
					try (PreparedStatement st = c.prepareStatement("DELETE FROM claims WHERE id = ? OR parent_id = ?")) {
						for (String id : ch.removed) {
							st.setObject(1, UUID.fromString(id));
							st.setObject(2, UUID.fromString(id));
							st.addBatch();
						}
						st.executeBatch();
					}
				}
				try (PreparedStatement st = c.prepareStatement("""
						INSERT INTO claim_players (player_uuid, name, accrued, bonus, bought, play_minutes, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT (player_uuid) DO UPDATE SET name = COALESCE(EXCLUDED.name, claim_players.name), accrued = EXCLUDED.accrued,
						    bonus = EXCLUDED.bonus, bought = EXCLUDED.bought, play_minutes = EXCLUDED.play_minutes, last_seen = EXCLUDED.last_seen""")) {
					for (Map.Entry<String, Claims.PlayerBlocks> e : ch.players.entrySet()) {
						Claims.PlayerBlocks p = e.getValue();
						st.setObject(1, UUID.fromString(e.getKey()));
						st.setString(2, p.name);
						st.setInt(3, p.accrued);
						st.setInt(4, p.bonus);
						st.setInt(5, p.bought);
						st.setLong(6, p.playMinutes);
						st.setTimestamp(7, p.lastSeen > 0 ? new Timestamp(p.lastSeen) : null);
						st.addBatch();
					}
					st.executeBatch();
				}
				long now = System.currentTimeMillis();
				try (PreparedStatement st = c.prepareStatement("""
						INSERT INTO claim_sync (id, world_id, rev, synced_at) VALUES (1, ?, ?, ?)
						ON CONFLICT (id) DO UPDATE SET world_id = EXCLUDED.world_id, rev = EXCLUDED.rev, synced_at = EXCLUDED.synced_at""")) {
					st.setString(1, ch.worldId);
					st.setLong(2, ch.rev);
					st.setTimestamp(3, new Timestamp(now));
					st.executeUpdate();
				}
				c.commit();
				syncedRev = ch.rev;
				syncedWorld = ch.worldId;
				syncedAt = now;
			} catch (SQLException | RuntimeException e) {
				c.rollback();
				throw e;
			}
		}
	}

	// ---------------------------------------------------------------- editing

	private String editState() {
		Supervisor.State state = server.state();
		if (state == Supervisor.State.STARTING || state == Supervisor.State.STOPPING) return "Wait until the server has started or stopped.";
		return null;
	}

	/** Runs an edit on the mod (server on) or the world file (server off). Returns the error, or null. */
	private String edit(JsonObject action, String user) throws IOException, InterruptedException {
		String busy = editState();
		if (busy != null) return busy;
		if (server.state() == Supervisor.State.RUNNING) {
			return dashboard.post("/api/claims/action", action, user);
		}
		synchronized (this) {
			Path f = worldFile();
			if (!Files.isDirectory(f.getParent())) return "There's no world yet.";
			Claims.Data data = Claims.readData(f);
			data.borders = borders();
			Map<String, Object> result = Claims.apply(data, settings(), action, System.currentTimeMillis());
			if (result.containsKey("error")) return String.valueOf(result.get("error"));
			Claims.writeData(f, data);
		}
		if (db != null) sync();
		return null;
	}

	/** A player's UUID from the server's name cache, the claims data or the online list. */
	private String uuidOf(String name, Changes data) {
		if (name == null || name.isBlank()) return null;
		String n = name.strip();
		for (Map.Entry<String, Claims.PlayerBlocks> e : data.players.entrySet()) {
			if (e.getValue().name != null && e.getValue().name.equalsIgnoreCase(n)) return e.getKey();
		}
		try {
			Path cache = serverDir.resolve("usercache.json");
			if (Files.exists(cache)) {
				for (JsonElement el : JsonParser.parseString(Files.readString(cache)).getAsJsonArray()) {
					JsonObject o = el.getAsJsonObject();
					if (o.has("name") && o.get("name").getAsString().equalsIgnoreCase(n) && o.has("uuid")) return o.get("uuid").getAsString();
				}
			}
		} catch (IOException | RuntimeException e) {
			// Not known.
		}
		return null;
	}

	private String nameOf(String uuid, Changes data) {
		Claims.PlayerBlocks p = data.players.get(uuid);
		return p == null ? null : p.name;
	}

	// ---------------------------------------------------------------- for the website (WebProfile)

	/** All claims and players right now (from the mod or the world file). */
	Changes current() throws IOException {
		return live();
	}

	/** Runs an already checked edit for the website; returns the error, or null. */
	String editFor(JsonObject action, String actor) throws IOException, InterruptedException {
		return edit(action, actor);
	}

	/** A player's UUID by name (server name cache, claims data). */
	String playerUuid(String name, Changes data) {
		return uuidOf(name, data);
	}

	String playerName(String uuid, Changes data) {
		return nameOf(uuid, data);
	}

	// ---------------------------------------------------------------- routes

	void register(RoutesConfig routes) {
		routes.get("/api/claims", guard(Permissions.CLAIMS_VIEW, (ctx, me) -> {
			Changes data = live();
			Claims.Settings s = settings();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("enabled", s.enabled);
			out.put("running", server.state() == Supervisor.State.RUNNING);
			out.put("busy", editState());
			out.put("hasWorld", data.worldId != null);
			// Claims with their size, subclaim count and the owner's block numbers.
			Map<String, Long> used = new HashMap<>();
			Map<String, Integer> subs = new HashMap<>();
			Map<String, Integer> count = new HashMap<>();
			for (Claims.Claim c : data.claims) {
				if (c.parent != null) subs.merge(c.parent, 1, Integer::sum);
				else if (c.owner != null) {
					used.merge(c.owner, c.box().area(), Long::sum);
					count.merge(c.owner, 1, Integer::sum);
				}
			}
			// For the expiry dates: the owners' last visits.
			Claims.Data all = new Claims.Data();
			for (Claims.Claim c : data.claims) all.claims.put(c.id, c);
			all.players = data.players;
			out.put("inactiveDays", s.inactiveDays);
			List<Map<String, Object>> claims = new ArrayList<>();
			for (Claims.Claim c : data.claims) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", c.id);
				m.put("parent", c.parent);
				m.put("owner", c.owner);
				m.put("ownerName", c.ownerName);
				m.put("dim", c.dim);
				m.put("minX", c.minX);
				m.put("minZ", c.minZ);
				m.put("maxX", c.maxX);
				m.put("maxZ", c.maxZ);
				m.put("width", c.box().width());
				m.put("length", c.box().length());
				m.put("area", c.box().area());
				m.put("name", c.name);
				m.put("displayName", c.displayName());
				m.put("color", Claims.hex(c.color));
				m.put("flags", c.flags);
				List<Map<String, Object>> members = new ArrayList<>();
				c.members.forEach((id, mem) -> members.add(Map.of("uuid", id, "name", mem.name == null ? id : mem.name, "role", mem.role.name())));
				m.put("members", members);
				m.put("subclaims", subs.getOrDefault(c.id, 0));
				m.put("created", c.created);
				m.put("updated", c.updated);
				m.put("expires", all.expiresAt(c, s));
				m.put("keepUntil", c.keepUntil);
				Claims.PlayerBlocks owner = c.owner == null ? null : data.players.get(c.owner);
				m.put("ownerLastSeen", owner == null ? 0 : owner.lastSeen);
				claims.add(m);
			}
			out.put("claims", claims);
			List<Map<String, Object>> players = new ArrayList<>();
			data.players.forEach((id, p) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				long u = used.getOrDefault(id, 0L);
				m.put("uuid", id);
				m.put("name", p.name);
				m.put("accrued", p.accrued);
				m.put("bonus", p.bonus);
				m.put("bought", p.bought);
				m.put("used", u);
				m.put("available", (long) p.accrued + p.bonus + p.bought - u);
				m.put("claims", count.getOrDefault(id, 0));
				m.put("playMinutes", p.playMinutes);
				m.put("lastSeen", p.lastSeen);
				players.add(m);
			});
			out.put("players", players);
			out.put("roles", java.util.Arrays.stream(Claims.Role.values()).filter(r -> r != Claims.Role.OWNER)
					.map(r -> Map.of("id", r.name(), "label", r.label, "description", r.description)).toList());
			out.put("flags", java.util.Arrays.stream(Claims.Flags.LABELS).map(f -> Map.of("id", f[0], "label", f[1])).toList());
			Map<String, Object> database = new LinkedHashMap<>();
			database.put("configured", db != null);
			database.put("ready", db != null && db.ready());
			database.put("problem", db == null ? null : db.ready() ? syncProblem : db.problem());
			database.put("syncedAt", syncedAt);
			out.put("database", database);
			json(ctx, out);
		}));

		routes.post("/api/claims/action", guard(Permissions.CLAIMS_MANAGE, (ctx, me) -> {
			JsonObject body = Web.body(ctx);
			String action = Web.str(body, "action");
			if (!List.of("create", "resize", "delete", "update", "member_set", "member_remove", "transfer", "blocks", "keep").contains(action)) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unknown action.");
				return;
			}
			// Names typed in the panel → UUIDs.
			Changes data = live();
			for (String[] pair : new String[][] { { "uuid", "playerName" }, { "owner", "ownerName" } }) {
				String typed = Web.str(body, pair[1]).strip();
				if (Web.str(body, pair[0]).isEmpty() && !typed.isEmpty()) {
					String id = uuidOf(typed, data);
					if (id == null) {
						error(ctx, HttpStatus.BAD_REQUEST, "Nobody called \"" + typed + "\" has played on the server.");
						return;
					}
					body.addProperty(pair[0], id);
					String known = nameOf(id, data);
					body.addProperty(pair[0].equals("uuid") ? "name" : "ownerName", known != null && known.equalsIgnoreCase(typed) ? known : typed);
				}
			}
			String err = edit(body, me.name());
			if (err != null) {
				error(ctx, HttpStatus.CONFLICT, err);
				return;
			}
			audit.log(me.name(), "claims." + action, describe(body), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.get("/api/claims/settings", guard(Permissions.CLAIMS_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", settings());
			out.put("defaults", new Claims.Settings().fill());
			Map<String, Object> areas = new LinkedHashMap<>();
			borders().forEach((dim, b) -> {
				if (b.soft > 0) areas.put(dim, Map.of("shape", b.shape, "x", b.x, "z", b.z, "radius", b.soft));
			});
			out.put("permanentAreas", areas);
			// Every dimension of the world (and ones still in the settings that the world doesn't have).
			List<Map<String, Object>> dims = new ArrayList<>();
			for (MapService.Dim d : map.dimensions()) dims.add(Map.of("id", d.id(), "name", d.name()));
			for (String id : settings().dimensions) {
				if (dims.stream().noneMatch(d -> d.get("id").equals(id))) dims.add(Map.of("id", id, "name", id, "missing", true));
			}
			out.put("dimensions", dims);
			json(ctx, out);
		}));
		routes.post("/api/claims/settings", guard(Permissions.CLAIMS_MANAGE, (ctx, me) -> {
			Claims.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Claims.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Claims.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Claims.Settings before = settings();
			Claims.write(settingsFile(), next);
			audit.log(me.name(), before.enabled != next.enabled ? "claims" : "claims.settings",
					before.enabled != next.enabled ? (next.enabled ? "on" : "off") : "saved", clientIp(ctx));
			json(ctx, Map.of("ok", true, "running", server.running()));
		}));

		routes.get("/api/claims/goml", guard(Permissions.CLAIMS_MANAGE, (ctx, me) -> {
			if (server.state() != Supervisor.State.RUNNING) {
				error(ctx, HttpStatus.CONFLICT, "Start the server first: GOML's claims are read from the running game.");
				return;
			}
			JsonObject answer = dashboard.get("/api/claims/goml");
			if (answer == null) {
				error(ctx, HttpStatus.BAD_GATEWAY, "The mod doesn't answer (it needs Nice Control Center 1.9.0 or newer).");
				return;
			}
			json(ctx, answer);
		}));
		routes.post("/api/claims/goml/import", guard(Permissions.CLAIMS_MANAGE, (ctx, me) -> {
			if (server.state() != Supervisor.State.RUNNING) {
				error(ctx, HttpStatus.CONFLICT, "Start the server first.");
				return;
			}
			JsonObject answer = dashboard.postForJson("/api/claims/goml/import", new JsonObject(), me.name());
			if (answer == null) {
				error(ctx, HttpStatus.BAD_GATEWAY, "The mod doesn't answer.");
				return;
			}
			audit.log(me.name(), "claims.goml-import", answer.has("imported") ? answer.get("imported").getAsInt() + " claims" : "", clientIp(ctx));
			json(ctx, answer);
		}));
	}

	private static String describe(JsonObject body) {
		StringBuilder sb = new StringBuilder();
		for (String key : new String[] { "claim", "name", "playerName", "ownerName", "role", "color", "bonus", "minX", "minZ", "maxX", "maxZ", "dim", "until" }) {
			if (body.has(key) && !body.get(key).isJsonNull()) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(key).append(' ').append(body.get(key).getAsString());
			}
		}
		return sb.length() > 300 ? sb.substring(0, 300) : sb.toString();
	}

	/** For the public info page: what players need to know about claiming (null when claims are off). */
	Map<String, Object> publicInfo() {
		Claims.Settings s = settings();
		if (!s.enabled) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("startingBlocks", s.startingBlocks);
		m.put("blocksPerHour", s.blocksPerHour);
		m.put("maxAccruedBlocks", s.maxAccruedBlocks);
		m.put("afkMinutes", s.afkMinutes);
		m.put("minWidth", s.minWidth);
		m.put("minArea", s.minArea);
		m.put("maxClaims", s.maxClaims);
		m.put("dimensions", s.dimensions);
		m.put("onlyPermanentArea", s.onlyPermanentArea);
		m.put("inactiveDays", s.inactiveDays);
		eu.explorerseden.nicecontrolcenter.Market.Settings market = eu.explorerseden.nicecontrolcenter.Market.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Market.FILE));
		List<Map<String, Object>> offers = new ArrayList<>();
		if (market.enabled) for (eu.explorerseden.nicecontrolcenter.Market.ClaimOffer o : market.claimBlocks) {
			if (o.onSale()) offers.add(Map.of("blocks", o.blocks, "price", o.price, "until", o.saleEnds));
		}
		if (!offers.isEmpty()) m.put("shop", Map.of("currency", market.currency, "offers", offers, "max", market.claimBlocksMax));
		m.put("roles", java.util.Arrays.stream(Claims.Role.values()).filter(r -> r != Claims.Role.OWNER)
				.map(r -> Map.of("label", r.label, "description", r.description)).toList());
		return m;
	}
}
