package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.clientIp;
import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.guard;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The player market in the panel: settings, the live listings (from the mod while the server runs, from
 * the world file while it's off), taking a listing off, item icons, a copy for the public info page, and
 * a copy in the database for a website.
 */
final class MarketService {
	/** What /api/market/changes and the world file give. */
	static final class Changes {
		String worldId;
		long rev;
		boolean full;
		List<Market.Listing> listings = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		List<Market.Sale> sales = new ArrayList<>();
	}

	private final Path serverDir;
	private final Path cacheFile;
	private final Db db;
	private final Supervisor server;
	private final DashboardProxy dashboard;
	private final Audit audit;
	private final ItemIcons icons;
	/** The database copy was written since the panel started. */
	private boolean written;
	private volatile long syncedRev = -1;
	private volatile String syncedWorld;
	private volatile long fileModified = -1;

	MarketService(Path serverDir, Path dataDir, Db db, Supervisor server, DashboardProxy dashboard, Audit audit, ItemIcons icons) {
		this.serverDir = serverDir;
		this.cacheFile = dataDir.resolve("market-cache.json");
		this.db = db;
		this.server = server;
		this.dashboard = dashboard;
		this.audit = audit;
		this.icons = icons;
	}

	ItemIcons icons() {
		return icons;
	}

	Path settingsFile() {
		return serverDir.resolve("config").resolve(Market.FILE);
	}

	Market.Settings settings() {
		return Market.read(settingsFile());
	}

	private Path worldFile() {
		String name = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("level-name", "world").strip();
		return serverDir.resolve(name.isEmpty() ? "world" : name).resolve(Market.DATA_FILE);
	}

	void start() {
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "market-sync");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(this::sync, 20, 15, TimeUnit.SECONDS);
	}

	/** All listings now: from the mod while running, else from the world file. */
	Changes live() {
		if (server.state() == Supervisor.State.RUNNING) {
			JsonObject answer = dashboard.get("/api/market/changes?since=0");
			if (answer != null) return Json.GSON.fromJson(answer, Changes.class);
		}
		Changes c = new Changes();
		try {
			Path f = worldFile();
			Market.Data d = Market.readData(f);
			c.worldId = Files.exists(f) ? d.worldId : null;
			c.rev = d.rev;
			c.full = true;
			c.listings = new ArrayList<>(d.listings.values());
			c.sales = d.sales;
		} catch (IOException | RuntimeException e) {
			// Nothing to show.
		}
		return c;
	}

	/** The listings as the web shows them (icons included). */
	List<Map<String, Object>> listings(Changes c) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Market.Listing l : c.listings) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", l.id);
			m.put("name", l.item.name);
			m.put("item", l.item.id);
			m.put("lore", l.item.lore);
			m.put("enchantments", l.item.enchantments);
			m.put("amount", l.amount);
			m.put("price", l.price);
			m.put("seller", l.sellerName);
			m.put("sellerUuid", l.seller);
			m.put("created", l.created);
			m.put("expires", l.expires);
			String icon = icons.iconFor(l.item.stack);
			if (icon != null) m.put("icon", icon);
			out.add(m);
		}
		out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
		return out;
	}

	/** What counts as a command change: ids and their commands (not names, prices or icons). */
	private static Map<String, List<String>> commandsOf(Market.Settings s) {
		Map<String, List<String>> out = new java.util.TreeMap<>();
		for (Market.CommandOffer o : s.commandOffers) out.put(o.id, o.commands);
		return out;
	}

	/**
	 * The public view: player listings without UUIDs (kept on disk so they show while the server is off) and
	 * what the server sells (from the settings).
	 */
	Map<String, Object> publicView() {
		Market.Settings s = settings();
		if (!s.enabled) return null;
		Map<String, Object> view = null;
		try {
			if (Files.exists(cacheFile)) {
				@SuppressWarnings("unchecked")
				Map<String, Object> cached = Json.GSON.fromJson(Files.readString(cacheFile), Map.class);
				view = cached;
			}
		} catch (IOException | RuntimeException e) {
			// Built again on the next sync.
		}
		List<Map<String, Object>> offers = serverOffers(s);
		if (view == null && offers.isEmpty()) return null;
		if (view == null) {
			view = new LinkedHashMap<>();
			view.put("currency", s.currency);
			view.put("listings", List.of());
		}
		view.put("server", offers);
		view.put("serverName", s.serverName);
		return view;
	}

	/** What the server sells right now, for the info page: claim blocks (while claims are on) and command listings. */
	private List<Map<String, Object>> serverOffers(Market.Settings s) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (eu.explorerseden.nicecontrolcenter.Claims.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Claims.FILE)).enabled) {
			for (Market.ClaimOffer o : s.claimBlocks) {
				if (!o.onSale()) continue;
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", String.format(java.util.Locale.ROOT, "%,d claim blocks", o.blocks));
				m.put("description", "For land claims: every claimed block costs one."
						+ (s.claimBlocksMax > 0 ? String.format(java.util.Locale.ROOT, " Up to %,d per player in all.", s.claimBlocksMax) : ""));
				m.put("price", o.price);
				if (o.saleEnds != 0) m.put("until", o.saleEnds);
				String icon = icons.iconFor(offerStack(o.icon, o.iconModel));
				if (icon != null) m.put("icon", icon);
				out.add(m);
			}
		}
		for (Market.CommandOffer o : s.commandOffers) {
			if (!o.onSale()) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", o.name);
			m.put("description", o.description);
			m.put("price", o.price);
			if (o.saleEnds != 0) m.put("until", o.saleEnds);
			if (o.perPlayer > 0) m.put("perPlayer", o.perPlayer);
			String icon = icons.iconFor(offerStack(o.icon, o.iconModel));
			if (icon != null) m.put("icon", icon);
			out.add(m);
		}
		return out;
	}

	private void writeCache(Changes c) {
		Market.Settings s = settings();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("currency", s.currency);
		String currencyIcon = icons.iconFor("{\"id\":\"" + s.currency + "\"}");
		if (currencyIcon != null) out.put("currencyIcon", currencyIcon);
		List<Map<String, Object>> list = listings(c);
		for (Map<String, Object> m : list) m.remove("sellerUuid");
		out.put("listings", list);
		out.put("updated", System.currentTimeMillis());
		try {
			Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Next time.
		}
	}

	// ---------------------------------------------------------------- sync

	private synchronized void sync() {
		try {
			if (!settings().enabled) return;
			icons.prepare();
			Supervisor.State state = server.state();
			Changes c;
			if (state == Supervisor.State.RUNNING) {
				JsonObject answer = dashboard.get("/api/market/changes?since=0");
				if (answer == null) return;
				c = Json.GSON.fromJson(answer, Changes.class);
			} else if (state == Supervisor.State.STOPPED || state == Supervisor.State.CRASHED) {
				Path f = worldFile();
				long modified = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
				if (modified == fileModified) return;
				fileModified = modified;
				c = live();
			} else {
				return;
			}
			writeCache(c);
			if (db != null && db.ready()) write(c);
		} catch (IOException | SQLException | RuntimeException e) {
			System.err.println("Market sync: " + e);
		}
	}

	/** The database copy, replaced as a whole (the market is small) when anything changed. */
	private void write(Changes c) throws SQLException {
		if (syncedRev < 0) {
			try (Connection con = db.connection(); PreparedStatement st = con.prepareStatement("SELECT world_id, rev FROM market_sync WHERE id = 1");
					ResultSet rs = st.executeQuery()) {
				if (rs.next()) {
					syncedWorld = rs.getString(1);
					syncedRev = rs.getLong(2);
				} else {
					syncedRev = 0;
				}
			}
		}
		// Once per start regardless, so new columns (icons) fill in after an update.
		if (written && c.rev == syncedRev && java.util.Objects.equals(c.worldId, syncedWorld)) return;
		try (Connection con = db.connection()) {
			con.setAutoCommit(false);
			try {
				try (PreparedStatement del = con.prepareStatement("DELETE FROM market_listings")) {
					del.executeUpdate();
				}
				try (PreparedStatement ins = con.prepareStatement("""
						INSERT INTO market_listings (id, seller_uuid, seller_name, item_id, item_name, item, lore, enchantments, amount, price, created_at, expires_at, icon)
						VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)""")) {
					for (Market.Listing l : c.listings) {
						ins.setObject(1, UUID.fromString(l.id));
						ins.setObject(2, UUID.fromString(l.seller));
						ins.setString(3, l.sellerName);
						ins.setString(4, l.item.id);
						ins.setString(5, l.item.name);
						ins.setString(6, l.item.stack);
						ins.setString(7, Json.GSON.toJson(l.item.lore));
						ins.setString(8, Json.GSON.toJson(l.item.enchantments));
						ins.setInt(9, l.amount);
						ins.setInt(10, l.price);
						ins.setTimestamp(11, new Timestamp(l.created));
						ins.setTimestamp(12, new Timestamp(l.expires));
						ins.setString(13, icons.iconFor(l.item.stack));
						ins.addBatch();
					}
					ins.executeBatch();
				}
				try (PreparedStatement ins = con.prepareStatement("""
						INSERT INTO market_sales (listing_id, item_id, item_name, seller_uuid, seller_name, buyer_uuid, buyer_name, amount, total, sold_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")) {
					for (Market.Sale s : c.sales) {
						ins.setObject(1, UUID.fromString(s.listing));
						ins.setString(2, s.itemId);
						ins.setString(3, s.itemName);
						ins.setObject(4, s.seller == null ? null : UUID.fromString(s.seller));
						ins.setString(5, s.sellerName);
						ins.setObject(6, UUID.fromString(s.buyer));
						ins.setString(7, s.buyerName);
						ins.setInt(8, s.amount);
						ins.setInt(9, s.total);
						ins.setTimestamp(10, new Timestamp(s.time));
						ins.addBatch();
					}
					ins.executeBatch();
				}
				try (PreparedStatement st = con.prepareStatement("""
						INSERT INTO market_sync (id, world_id, rev, synced_at, currency, currency_icon) VALUES (1, ?, ?, now(), ?, ?)
						ON CONFLICT (id) DO UPDATE SET world_id = EXCLUDED.world_id, rev = EXCLUDED.rev, synced_at = EXCLUDED.synced_at,
							currency = EXCLUDED.currency, currency_icon = EXCLUDED.currency_icon""")) {
					String currency = settings().currency;
					st.setString(1, c.worldId);
					st.setLong(2, c.rev);
					st.setString(3, currency);
					st.setString(4, icons.iconFor("{\"id\":\"" + currency + "\"}"));
					st.executeUpdate();
				}
				con.commit();
				syncedRev = c.rev;
				syncedWorld = c.worldId;
				written = true;
			} catch (SQLException | RuntimeException e) {
				con.rollback();
				throw e;
			}
		}
	}

	// ---------------------------------------------------------------- routes

	void register(RoutesConfig routes) {
		routes.get("/api/market", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Changes c = live();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("enabled", settings().enabled);
			out.put("running", server.state() == Supervisor.State.RUNNING);
			out.put("listings", listings(c));
			List<Market.Sale> sales = new ArrayList<>(c.sales);
			java.util.Collections.reverse(sales);
			out.put("sales", sales.size() > 200 ? sales.subList(0, 200) : sales);
			out.put("icons", icons.problem());
			json(ctx, out);
		}));
		routes.get("/api/market/settings", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Market.Settings s = settings();
			// Icon previews for the claim block listings ("" when there's none).
			List<String> claimIcons = new ArrayList<>();
			for (Market.ClaimOffer o : s.claimBlocks) {
				String icon = icons.iconFor(offerStack(o.icon, o.iconModel));
				claimIcons.add(icon == null ? "" : icon);
			}
			List<String> commandIcons = new ArrayList<>();
			for (Market.CommandOffer o : s.commandOffers) {
				String icon = icons.iconFor(offerStack(o.icon, o.iconModel));
				commandIcons.add(icon == null ? "" : icon);
			}
			json(ctx, Map.of("settings", s, "defaults", new Market.Settings().fill(), "claimIcons", claimIcons, "commandIcons", commandIcons,
					"variables", Market.COMMAND_VARIABLES, "canCommands", me.can(Permissions.CONSOLE_WRITE)));
		}));
		routes.post("/api/market/settings", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Market.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Market.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Market.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Market.Settings before = settings();
			// Command listings run as the console: changing them takes the right to run console commands.
			boolean commandsChanged = !Json.GSON.toJson(commandsOf(before)).equals(Json.GSON.toJson(commandsOf(next)));
			if (commandsChanged && !me.can(Permissions.CONSOLE_WRITE)) {
				error(ctx, HttpStatus.FORBIDDEN, "Changing the commands of command listings needs the right to run console commands.");
				return;
			}
			boolean was = before.enabled;
			Market.write(settingsFile(), next);
			if (commandsChanged) audit.log(me.name(), "market.commands", next.commandOffers.size() + " command listings", clientIp(ctx));
			audit.log(me.name(), "market.settings", was != next.enabled ? (next.enabled ? "on" : "off") : "saved", clientIp(ctx));
			json(ctx, Map.of("ok", true, "running", server.running()));
		}));
		routes.post("/api/market/remove", guard(Permissions.PLAYERS_MANAGE, (ctx, me) -> {
			String id = Web.str(Web.body(ctx), "id");
			String err;
			if (server.state() == Supervisor.State.RUNNING) {
				JsonObject b = new JsonObject();
				b.addProperty("id", id);
				err = dashboard.post("/api/market/remove", b, me.name());
			} else if (server.state() == Supervisor.State.STOPPED || server.state() == Supervisor.State.CRASHED) {
				synchronized (this) {
					Path f = worldFile();
					Market.Data d = Market.readData(f);
					Market.Listing l = d.listings.get(id);
					if (l == null) {
						err = "That listing is gone.";
					} else {
						d.withdraw(l);
						Market.writeData(f, d);
						err = null;
					}
				}
			} else {
				err = "Wait until the server has started or stopped.";
			}
			if (err != null) {
				error(ctx, HttpStatus.CONFLICT, err);
				return;
			}
			audit.log(me.name(), "market.remove", id, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		// Icons: public, for the info page.
		routes.get("/info/items/{hash}", ctx -> {
			String name = ctx.pathParam("hash").replaceFirst("\\.png$", "");
			Path f = icons.icon(name);
			if (f == null) {
				ctx.status(HttpStatus.NOT_FOUND);
				return;
			}
			ctx.contentType("image/png").header("Cache-Control", "public, max-age=86400").result(Files.readAllBytes(f));
		});
	}

	/** A server listing's icon as a stack: the item, with the item model (like one from the resource pack) when one is set. */
	private static String offerStack(String icon, String model) {
		JsonObject stack = new JsonObject();
		stack.addProperty("id", icon);
		if (model != null && !model.isBlank()) {
			JsonObject components = new JsonObject();
			components.addProperty("minecraft:item_model", model);
			stack.add("components", components);
		}
		return stack.toString();
	}
}
