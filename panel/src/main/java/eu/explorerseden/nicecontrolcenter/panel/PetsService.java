package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.clientIp;
import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.guard;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.Pets;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pets in the panel: the pet shop (settings file the mod reads), what was sold and who has pets (from the
 * mod while running, from the world file while it's off), and head pictures for the panel and the public
 * info page (the face of the skin, fetched once from textures.minecraft.net and kept).
 */
final class PetsService {
	private final Path serverDir;
	private final Path headDir;
	private final Supervisor server;
	private final DashboardProxy dashboard;
	private final Audit audit;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
	/** Skins that couldn't be fetched, and when (tried again after a while). */
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	/** The last pet numbers for the public page, so visitors don't each ask the mod. */
	private volatile Map<String, Object> publicState;
	private volatile long publicStateAt;

	PetsService(Path serverDir, Path dataDir, Supervisor server, DashboardProxy dashboard, Audit audit) {
		this.serverDir = serverDir;
		this.headDir = dataDir.resolve("heads");
		this.server = server;
		this.dashboard = dashboard;
		this.audit = audit;
	}

	Path settingsFile() {
		return serverDir.resolve("config").resolve(Pets.FILE);
	}

	Pets.Settings settings() {
		return Pets.read(settingsFile());
	}

	private Path worldFile() {
		String name = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("level-name", "world").strip();
		return serverDir.resolve(name.isEmpty() ? "world" : name).resolve(Pets.DATA_FILE);
	}

	/** Sold counts, owners per pet and purchases: from the mod while running, else from the world file. */
	@SuppressWarnings("unchecked")
	Map<String, Object> state() {
		if (server.state() == Supervisor.State.RUNNING) {
			JsonObject answer = dashboard.get("/api/pets/state");
			if (answer != null) return Json.GSON.fromJson(answer, Map.class);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			Pets.Data d = Pets.readData(worldFile());
			out.put("sold", d.sold);
			Map<String, Integer> owners = new LinkedHashMap<>();
			for (Pets.Owner o : d.owners.values()) for (Pets.Owned p : o.pets) owners.merge(p.pet, 1, Integer::sum);
			out.put("owners", owners);
			out.put("purchases", d.purchases);
		} catch (IOException | RuntimeException e) {
			out.put("sold", Map.of());
			out.put("owners", Map.of());
			out.put("purchases", List.of());
		}
		return out;
	}

	private static int number(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	/** The pets for sale for the public info page (null while pets are off). */
	@SuppressWarnings("unchecked")
	List<Map<String, Object>> publicView() {
		Pets.Settings s = settings();
		if (!s.enabled) return null;
		if (publicState == null || System.currentTimeMillis() - publicStateAt > 30_000) {
			publicState = state();
			publicStateAt = System.currentTimeMillis();
		}
		Map<String, Object> sold = (Map<String, Object>) publicState.getOrDefault("sold", Map.of());
		List<Map<String, Object>> out = new ArrayList<>();
		for (Pets.Pet p : s.pets) {
			if (!p.onSale()) continue;
			int left = p.stock <= 0 ? -1 : Math.max(0, p.stock - number(sold.get(p.id)));
			if (left == 0) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.id);
			m.put("kind", "pet");
			m.put("name", p.name);
			if (!p.description.isEmpty()) m.put("description", p.description);
			m.put("price", p.price);
			if (p.saleEnds != 0) m.put("until", p.saleEnds);
			m.put("left", left);
			m.put("head", Pets.skinHash(p.texture));
			if (s.perks.enabled && !p.perks.isEmpty()) {
				List<Map<String, Object>> perks = new ArrayList<>();
				for (int i = 0; i < p.perks.size(); i++) {
					Pets.Perk k = Pets.Perk.of(p.perks.get(i));
					if (k != null) perks.add(Map.of("label", k.label, "description", k.description, "level", Pets.slotLevel(i, s.perks)));
				}
				m.put("perks", perks);
			}
			out.add(m);
		}
		return out;
	}

	// ---------------------------------------------------------------- heads

	// ---------------------------------------------------------------- database copy

	private volatile long syncedModified = -1;

	/** Copies who owns which pet into the database for the website, once a minute when the world file changed. */
	void startDbSync(Db db) {
		if (db == null) return;
		java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "pets-sync");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(() -> {
			try {
				syncDb(db);
			} catch (IOException | java.sql.SQLException | RuntimeException e) {
				System.err.println("Pets sync: " + e);
			}
		}, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
	}

	private void syncDb(Db db) throws IOException, java.sql.SQLException {
		if (!db.ready()) return;
		Path f = worldFile();
		// Also when the settings change (perks, unlock levels, shop names).
		Path sf = settingsFile();
		long modified = (Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0) * 31 + (Files.exists(sf) ? Files.getLastModifiedTime(sf).toMillis() : 0);
		if (modified == syncedModified) return;
		Pets.Data d = Pets.readData(f);
		Pets.Settings settings = settings();
		try (java.sql.Connection con = db.connection()) {
			con.setAutoCommit(false);
			try {
				try (java.sql.PreparedStatement del = con.prepareStatement("DELETE FROM pets_owned")) {
					del.executeUpdate();
				}
				try (java.sql.PreparedStatement ins = con.prepareStatement("INSERT INTO pets_owned (player_uuid, pet_id, pet_name, head, since, active, nickname, name_color, bond_level, perks)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))")) {
					for (Map.Entry<String, Pets.Owner> e : d.owners.entrySet()) {
						java.util.UUID player;
						try {
							player = java.util.UUID.fromString(e.getKey());
						} catch (IllegalArgumentException bad) {
							continue;
						}
						for (Pets.Owned o : e.getValue().pets) {
							Pets.Pet shop = settings.pet(o.pet);
							// The shop's current head, like in game (PetStore.texture); the copy from the purchase only once the shop dropped the pet.
							String head = shop != null ? Pets.skinHash(shop.texture) : null;
							if (head == null) head = Pets.skinHash(o.texture);
							ins.setObject(1, player);
							ins.setString(2, o.pet);
							ins.setString(3, shop != null ? shop.name : o.name);
							ins.setString(4, head);
							ins.setTimestamp(5, o.since > 0 ? new java.sql.Timestamp(o.since) : null);
							ins.setBoolean(6, o.pet.equals(e.getValue().active));
							boolean named = settings.names && e.getValue().showName && o.nickname != null && !o.nickname.isEmpty();
							ins.setString(7, named ? o.nickname : null);
							ins.setString(8, named ? o.nameColor : null);
							int level = Pets.level(o.bond, settings.perks);
							ins.setObject(9, settings.perks.enabled ? level : null, java.sql.Types.INTEGER);
							com.google.gson.JsonArray perks = new com.google.gson.JsonArray();
							if (settings.perks.enabled && shop != null) {
								for (int i = 0; i < shop.perks.size(); i++) {
									Pets.Perk k = Pets.Perk.of(shop.perks.get(i));
									if (k == null) continue;
									JsonObject j = new JsonObject();
									j.addProperty("label", k.label);
									j.addProperty("description", k.description);
									j.addProperty("level", Pets.slotLevel(i, settings.perks));
									j.addProperty("unlocked", level >= Pets.slotLevel(i, settings.perks));
									perks.add(j);
								}
							}
							ins.setString(10, perks.toString());
							ins.addBatch();
						}
					}
					ins.executeBatch();
				}
				con.commit();
				syncedModified = modified;
			} catch (java.sql.SQLException | RuntimeException e) {
				con.rollback();
				throw e;
			}
		}
	}

	/** Its owners' faces may be shown on the info page too. */
	private volatile WaypointHubs waypointHubs;

	void useWaypointHubs(WaypointHubs hubs) {
		this.waypointHubs = hubs;
	}

	/** Server shop listings with head icons: their faces may be shown too. */
	private volatile MarketService market;

	void useMarket(MarketService market) {
		this.market = market;
	}

	/** The face picture for a skin hash (fetched once), or null. */
	Path head(String hash) {
		if (hash == null || !hash.matches("[0-9a-f]{32,128}")) return null;
		Path f = headDir.resolve(hash + ".png");
		if (Files.exists(f)) return f;
		Long when = failed.get(hash);
		if (when != null && System.currentTimeMillis() - when < 600_000) return null;
		if (failed.size() > 500) failed.clear();
		try {
			HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash)).timeout(Duration.ofSeconds(10)).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
			BufferedImage skin = ImageIO.read(new ByteArrayInputStream(res.body()));
			if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) throw new IOException("not a skin");
			int scale = skin.getWidth() / 64;
			// The face (8×8 at 8,8) with the hat layer (at 40,8) on top, 8 times as big.
			BufferedImage face = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 8; x++) {
					int base = skin.getRGB((8 + x) * scale, (8 + y) * scale) | 0xFF000000;
					int hat = skin.getRGB((40 + x) * scale, (8 + y) * scale);
					int c = (hat >>> 24) > 0x80 ? hat | 0xFF000000 : base;
					for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) face.setRGB(x * 8 + dx, y * 8 + dy, c);
				}
			}
			Files.createDirectories(headDir);
			Path tmp = headDir.resolve(hash + ".png.tmp");
			ImageIO.write(face, "png", tmp.toFile());
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			failed.remove(hash);
			return f;
		} catch (IOException | RuntimeException e) {
			failed.put(hash, System.currentTimeMillis());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	// ---------------------------------------------------------------- routes

	void register(RoutesConfig routes) {
		routes.get("/api/pets", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			Pets.Settings s = settings();
			Map<String, Object> state = state();
			// Pets taken out of the shop go for good once nobody has them any more.
			Object owners = state.get("owners");
			if (owners instanceof Map<?, ?> have && s.pets.removeIf(p -> p.retired && number(have.get(p.id)) == 0)) {
				Pets.write(settingsFile(), s);
			}
			out.put("settings", s);
			out.put("heads", heads(s));
			out.put("defaults", new Pets.Settings().fill());
			List<Map<String, Object>> perks = new ArrayList<>();
			for (Pets.Perk k : Pets.Perk.values()) perks.add(Map.of("id", k.name(), "label", k.label, "description", k.description, "group", k.group.name()));
			out.put("perks", perks);
			List<Map<String, Object>> reactions = new ArrayList<>();
			for (Pets.Reaction r : Pets.Reaction.values()) reactions.add(Map.of("id", r.name(), "label", r.label, "warning", r.warning, "cooldown", r.cooldown, "lines", r.lines));
			out.put("reactions", reactions);
			out.put("currency", Market.read(serverDir.resolve("config").resolve(Market.FILE)).currency);
			out.put("running", server.state() == Supervisor.State.RUNNING);
			Object purchases = state.get("purchases");
			if (purchases instanceof List<?> l) {
				List<Object> recent = new ArrayList<>(l);
				Collections.reverse(recent);
				state.put("purchases", recent.size() > 200 ? recent.subList(0, 200) : recent);
			}
			out.put("state", state);
			json(ctx, out);
		}));
		routes.post("/api/pets/settings", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Pets.Settings before = settings();
			Pets.Settings next;
			try {
				// Parts a page doesn't send (each page edits its own) stay as they are.
				JsonObject in = com.google.gson.JsonParser.parseString(ctx.body()).getAsJsonObject();
				JsonObject now = Json.GSON.toJsonTree(before).getAsJsonObject();
				for (Map.Entry<String, com.google.gson.JsonElement> e : now.entrySet()) if (!in.has(e.getKey())) in.add(e.getKey(), e.getValue());
				next = Json.GSON.fromJson(in, Pets.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Pets.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Pets.write(settingsFile(), next);
			String what = before.enabled != next.enabled ? (next.enabled ? "on" : "off") : next.pets.size() + " pets";
			audit.log(me.name(), "pets.settings", what, clientIp(ctx));
			json(ctx, Map.of("ok", true, "running", server.running(), "heads", heads(next)));
		}));
		// Checks a texture before it's added: its stored form and face.
		routes.post("/api/pets/texture", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			String t = Pets.texture(Web.str(Web.body(ctx), "texture"));
			if (t == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "That isn't a head texture: paste the base64 value from a head website (minecraft-heads.com, \"Value\") or a textures.minecraft.net link.");
				return;
			}
			json(ctx, Map.of("texture", t, "head", Pets.skinHash(t)));
		}));
		// Any face, for the preview while adding a pet (signed in only).
		routes.get("/api/pets/head/{hash}", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Path f = head(ctx.pathParam("hash").replaceFirst("\\.png$", ""));
			if (f == null) {
				ctx.status(HttpStatus.NOT_FOUND);
				return;
			}
			ctx.contentType("image/png").header("Cache-Control", "private, max-age=604800").result(Files.readAllBytes(f));
		}));
		// Faces of the shop's pets, server shop head icons and public waypoint hubs' owners: public, for the info page (only those, so
		// nobody can make the panel fetch others).
		// The map has the same ones under /map/ (on the map's own host name only /map/ exists).
		io.javalin.http.Handler faces = ctx -> {
			String hash = ctx.pathParam("hash").replaceFirst("\\.png$", "");
			WaypointHubs hubs = waypointHubs;
			MarketService shop = market;
			boolean known = settings().pets.stream().anyMatch(p -> hash.equals(Pets.skinHash(p.texture))) || hubs != null && hubs.isOwnerHead(hash)
					|| shop != null && shop.isOfferHead(hash);
			Path f = known ? head(hash) : null;
			if (f == null) {
				ctx.status(HttpStatus.NOT_FOUND);
				return;
			}
			ctx.contentType("image/png").header("Cache-Control", "public, max-age=604800").result(Files.readAllBytes(f));
		};
		routes.get("/info/heads/{hash}", faces);
		routes.get("/map/heads/{hash}", faces);
	}

	/** Pet id → skin hash, for the face pictures. */
	private static Map<String, String> heads(Pets.Settings s) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Pets.Pet p : s.pets) out.put(p.id, Pets.skinHash(p.texture));
		return out;
	}
}
