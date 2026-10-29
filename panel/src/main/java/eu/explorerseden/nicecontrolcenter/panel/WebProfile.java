package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Market;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * My SMP Profile on the website: a player's claims, waypoint hubs and market listings, live, and the
 * changes players may make there: a claim's name, colour, switches and members (with the same rights as
 * in game), a hub's name, description, colour, access and trusted players, and taking a listing back.
 * Same token and Discord check as {@link WebShop}. Claims work with the server on or off; hubs and
 * listings need it running.
 */
final class WebProfile {
	private final WebShop shop;
	private final ClaimService claims;
	private final MarketService market;
	private final DashboardProxy dashboard;
	private final Supervisor server;
	private final Audit audit;
	private final PlayerNotices notices;
	/** discordId → last change (ms), a few per second at most. */
	private final Map<String, Long> lastEdit = new ConcurrentHashMap<>();

	WebProfile(WebShop shop, ClaimService claims, MarketService market, DashboardProxy dashboard, Supervisor server, Audit audit, PlayerNotices notices) {
		this.notices = notices;
		this.shop = shop;
		this.claims = claims;
		this.market = market;
		this.dashboard = dashboard;
		this.server = server;
		this.audit = audit;
	}

	void register(RoutesConfig routes) {
		routes.post("/info/web/profile", ctx -> {
			Discord.Link link = player(ctx);
			if (link == null) return;
			boolean running = server.state() == Supervisor.State.RUNNING;
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("running", running);
			out.put("claims", claimsOf(link.uuid()));
			out.put("claimBlocks", blocksOf(link.uuid()));
			out.put("notices", Map.of("available", notices.available(), "kinds", notices.prefs(link.uuid())));
			Claims.Settings cs = claims.settings();
			out.put("claimsOn", cs.enabled);
			out.put("claimColors", cs.colors);
			out.put("flags", java.util.Arrays.stream(Claims.Flags.LABELS).map(f -> Map.of("id", f[0], "label", f[1])).toList());
			out.put("roles", java.util.Arrays.stream(Claims.Role.values()).filter(r -> r != Claims.Role.OWNER)
					.map(r -> Map.of("id", r.name(), "label", r.label, "description", r.description)).toList());
			if (running) {
				JsonObject answer = mod("hubs", link, null);
				if (answer != null && !answer.has("error")) {
					out.put("hubs", answer.get("hubs"));
					out.put("hubColors", answer.get("colors"));
					out.put("hubMaxText", answer.get("maxText"));
				}
				JsonObject account = mod("account", link, null);
				if (account != null && account.has("listings")) out.put("listings", account.get("listings"));
				if (account != null && account.has("maxPrice")) out.put("maxPrice", account.get("maxPrice"));
				if (account != null && account.has("hasSpotlight")) out.put("hasSpotlight", account.get("hasSpotlight"));
				if (account != null && account.has("seen")) out.put("seen", account.get("seen"));
				JsonObject pets = mod("pets", link, null);
				if (pets != null && !pets.has("error")) out.put("pets", pets);
			} else {
				Market.Data offline = market.worldData();
				List<Map<String, Object>> listings = new ArrayList<>();
				if (offline != null) for (Market.Listing l : offline.listings.values()) {
					if (!link.uuid().equalsIgnoreCase(l.seller)) continue;
					listings.add(Map.of("id", l.id, "name", l.item.name, "item", l.item.id, "amount", l.amount, "price", l.price, "per", l.lot(), "expires", l.expires, "spotlight", l.spotlight));
				}
				out.put("listings", listings);
			}
			json(ctx, out);
		});

		routes.post("/info/web/claim", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in)) return;
			if (!claims.settings().enabled) {
				error(ctx, HttpStatus.CONFLICT, "Land claims are switched off.");
				return;
			}
			ClaimService.Changes now = claims.current();
			Claims.Data data = new Claims.Data();
			for (Claims.Claim c : now.claims) data.claims.put(c.id, c);
			Claims.Claim c = data.claims.get(Web.str(in, "claim"));
			if (c == null) {
				error(ctx, HttpStatus.NOT_FOUND, "That claim doesn't exist any more.");
				return;
			}
			String me = link.uuid().toLowerCase(Locale.ROOT);
			Claims.Role role = data.roleOf(c, me);
			// The same rights as the claim menu in game: co-owners and the owner change a claim, only the owner
			// makes or unmakes co-owners.
			if (role == null || !role.includes(Claims.Role.MANAGE)) {
				error(ctx, HttpStatus.FORBIDDEN, "Only the owner and co-owners can change this claim.");
				return;
			}
			boolean owner = role == Claims.Role.OWNER;
			JsonObject action = new JsonObject();
			action.addProperty("claim", c.id);
			String what = Web.str(in, "action");
			String detail;
			switch (what) {
				case "update" -> {
					action.addProperty("action", "update");
					if (in.has("name")) action.addProperty("name", Web.str(in, "name"));
					if (in.has("description")) action.addProperty("description", Web.str(in, "description"));
					if (in.has("announce") && in.get("announce").isJsonPrimitive()) action.addProperty("announce", in.get("announce").getAsBoolean());
					if (in.has("color")) {
						String color = Web.str(in, "color");
						if (claims.settings().colors.values().stream().noneMatch(v -> v.equalsIgnoreCase(color))) {
							error(ctx, HttpStatus.BAD_REQUEST, "Pick one of the colours.");
							return;
						}
						action.addProperty("color", color);
					}
					if (in.has("flags") && in.get("flags").isJsonObject()) action.add("flags", in.getAsJsonObject("flags"));
					detail = "updated " + c.displayName();
				}
				case "member_set" -> {
					String typed = Web.str(in, "playerName");
					String uuid = Web.str(in, "memberUuid");
					if (uuid.isEmpty()) uuid = claims.playerUuid(typed, now);
					if (uuid == null || uuid.isEmpty()) {
						error(ctx, HttpStatus.BAD_REQUEST, "Nobody called \"" + typed + "\" has played on the server.");
						return;
					}
					uuid = uuid.toLowerCase(Locale.ROOT);
					Claims.Role next = Claims.Role.parse(Web.str(in, "role"));
					if (next == null || next == Claims.Role.OWNER) {
						error(ctx, HttpStatus.BAD_REQUEST, "Pick a role.");
						return;
					}
					Claims.Member before = c.members.get(uuid);
					if (!owner && (next == Claims.Role.MANAGE || before != null && before.role == Claims.Role.MANAGE)) {
						error(ctx, HttpStatus.FORBIDDEN, "Only the owner can make or change co-owners.");
						return;
					}
					if (uuid.equals(me)) {
						error(ctx, HttpStatus.BAD_REQUEST, "You can't change your own role.");
						return;
					}
					String known = claims.playerName(uuid, now);
					String name = known != null ? known : before != null && before.name != null ? before.name : typed;
					action.addProperty("action", "member_set");
					action.addProperty("uuid", uuid);
					action.addProperty("name", name);
					action.addProperty("role", next.name());
					detail = name + " → " + next.label + " in " + c.displayName();
				}
				case "member_remove" -> {
					String uuid = Web.str(in, "memberUuid").toLowerCase(Locale.ROOT);
					Claims.Member before = c.members.get(uuid);
					if (before == null) {
						error(ctx, HttpStatus.NOT_FOUND, "That player isn't a member any more.");
						return;
					}
					if (!owner && before.role == Claims.Role.MANAGE) {
						error(ctx, HttpStatus.FORBIDDEN, "Only the owner can remove co-owners.");
						return;
					}
					action.addProperty("action", "member_remove");
					action.addProperty("uuid", uuid);
					detail = "removed " + (before.name == null ? uuid : before.name) + " from " + c.displayName();
				}
				default -> {
					error(ctx, HttpStatus.BAD_REQUEST, "Unknown change.");
					return;
				}
			}
			String problem = claims.editFor(action, "website: " + link.name());
			if (problem != null) {
				error(ctx, HttpStatus.CONFLICT, problem);
				return;
			}
			audit.log("website", "claims.web", link.name() + " " + detail, Web.clientIp(ctx));
			json(ctx, Map.of("ok", true, "claims", claimsOf(link.uuid())));
		});

		routes.post("/info/web/hub", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in) || !running(ctx, "Waypoint hubs can be changed while the server runs.")) return;
			JsonObject body = new JsonObject();
			body.addProperty("id", Web.str(in, "id"));
			for (String k : List.of("name", "description", "color")) if (in.has(k)) body.addProperty(k, Web.str(in, k));
			if (in.has("public")) body.addProperty("public", in.get("public").getAsBoolean());
			if (in.has("trusted") && in.get("trusted").isJsonArray()) body.add("trusted", in.getAsJsonArray("trusted"));
			JsonObject answer = mod("hub", link, body);
			if (!answered(ctx, answer)) return;
			audit.log("website", "hubs.web", link.name() + " changed hub " + Web.str(answer, "name"), Web.clientIp(ctx));
			json(ctx, answer);
		});

		routes.post("/info/web/listing", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in) || !running(ctx, "Listings can be changed while the server runs.")) return;
			JsonObject body = new JsonObject();
			body.addProperty("id", Web.str(in, "id"));
			for (String k : List.of("price", "per", "amount", "spotlight")) if (in.has(k) && !in.get(k).isJsonNull()) body.add(k, in.get(k));
			JsonObject answer = mod("listing", link, body);
			if (!answered(ctx, answer)) return;
			audit.log("website", "market.webedit", link.name() + " changed a listing: " + Web.str(answer, "message"), Web.clientIp(ctx));
			market.syncSoon();
			json(ctx, answer);
		});

		routes.post("/info/web/pet", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in) || !running(ctx, "Pets can be changed while the server runs.")) return;
			JsonObject body = new JsonObject();
			for (String k : List.of("change", "pet", "how", "perk", "nickname", "color")) if (in.has(k) && !in.get(k).isJsonNull()) body.addProperty(k, Web.str(in, k));
			if (in.has("on") && !in.get("on").isJsonNull()) body.addProperty("on", in.get("on").getAsBoolean());
			JsonObject answer = mod("pet", link, body);
			if (!answered(ctx, answer)) return;
			audit.log("website", "pets.web", link.name() + ": " + Web.str(answer, "message"), Web.clientIp(ctx));
			json(ctx, answer);
		});

		routes.post("/info/web/notices", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in)) return;
			String kind = Web.str(in, "kindName");
			if (!PlayerNotices.KINDS.contains(kind) || !in.has("on")) {
				error(ctx, HttpStatus.BAD_REQUEST, "Which messages?");
				return;
			}
			notices.setPref(link.uuid(), kind, in.get("on").getAsBoolean());
			json(ctx, Map.of("ok", true, "kinds", notices.prefs(link.uuid())));
		});

		routes.post("/info/web/withdraw", ctx -> {
			JsonObject in = shop.authorized(ctx);
			Discord.Link link = in == null ? null : linkOf(ctx, in);
			if (link == null || !slowDown(ctx, in) || !running(ctx, "Listings can be taken back while the server runs.")) return;
			JsonObject body = new JsonObject();
			body.addProperty("id", Web.str(in, "id"));
			JsonObject answer = mod("withdraw", link, body);
			if (!answered(ctx, answer)) return;
			audit.log("website", "market.webwithdraw", link.name() + ": " + Web.str(answer, "message"), Web.clientIp(ctx));
			market.syncSoon();
			json(ctx, answer);
		});
	}

	/** The player's claims (owned, member of, subclaims of their claims) with what they may change. */
	private List<Map<String, Object>> claimsOf(String uuid) throws java.io.IOException {
		ClaimService.Changes now = claims.current();
		Claims.Data data = new Claims.Data();
		for (Claims.Claim c : now.claims) data.claims.put(c.id, c);
		data.players = now.players;
		Claims.Settings settings = claims.settings();
		String me = uuid.toLowerCase(Locale.ROOT);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Claims.Claim c : now.claims) {
			Claims.Role role = data.roleOf(c, me);
			if (role == null) continue;
			Claims.Claim parent = c.parent == null ? null : data.claims.get(c.parent);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", c.id);
			m.put("parent", c.parent);
			m.put("parentName", parent == null ? null : parent.displayName());
			m.put("name", c.name == null ? "" : c.name);
			m.put("description", c.description == null ? "" : c.description);
			m.put("announce", c.announce);
			m.put("displayName", c.displayName());
			m.put("ownerName", c.ownerName);
			m.put("color", Claims.hex(c.color));
			m.put("dim", c.dim);
			m.put("x", (c.minX + c.maxX) / 2);
			m.put("z", (c.minZ + c.maxZ) / 2);
			m.put("width", c.box().width());
			m.put("length", c.box().length());
			m.put("area", c.box().area());
			m.put("flags", c.flags);
			List<Map<String, Object>> members = new ArrayList<>();
			c.members.forEach((id, mem) -> members.add(Map.of("uuid", id, "name", mem.name == null ? id : mem.name, "role", mem.role.name(), "label", mem.role.label)));
			m.put("members", members);
			m.put("role", role.name());
			m.put("roleLabel", role.label);
			m.put("canEdit", role.includes(Claims.Role.MANAGE));
			m.put("isOwner", role == Claims.Role.OWNER);
			m.put("expires", data.expiresAt(c, settings));
			m.put("keepUntil", c.keepUntil);
			out.add(m);
		}
		// Claims first, each followed by its subclaims.
		out.sort((a, b) -> {
			String ka = (a.get("parent") == null ? a.get("id") : a.get("parent")) + (a.get("parent") == null ? "0" : "1" + a.get("displayName"));
			String kb = (b.get("parent") == null ? b.get("id") : b.get("parent")) + (b.get("parent") == null ? "0" : "1" + b.get("displayName"));
			return ka.compareTo(kb);
		});
		return out;
	}

	/** The player's claim blocks: earned by playing, given by admins, bought, used by their claims, and left. */
	private Map<String, Object> blocksOf(String uuid) throws java.io.IOException {
		ClaimService.Changes now = claims.current();
		String me = uuid.toLowerCase(Locale.ROOT);
		Claims.PlayerBlocks p = now.players.get(me);
		long used = 0;
		for (Claims.Claim c : now.claims) if (c.parent == null && me.equals(c.owner)) used += c.box().area();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("earned", p == null ? 0 : p.accrued);
		out.put("bonus", p == null ? 0 : p.bonus);
		out.put("bought", p == null ? 0 : p.bought);
		out.put("used", used);
		out.put("left", (p == null ? 0L : (long) p.accrued + p.bonus + p.bought) - used);
		out.put("inactiveDays", claims.settings().inactiveDays);
		return out;
	}

	/** The linked player named by "uuid" in a profile request; answers and returns null when it isn't theirs. */
	private Discord.Link player(Context ctx) {
		JsonObject in = shop.authorized(ctx);
		return in == null ? null : linkOf(ctx, in);
	}

	private Discord.Link linkOf(Context ctx, JsonObject in) {
		String uuid = Web.str(in, "uuid");
		Discord.Link link = shop.linksOf(Web.str(in, "discordId")).stream().filter(l -> l.uuid().equalsIgnoreCase(uuid)).findFirst().orElse(null);
		if (link == null) error(ctx, HttpStatus.FORBIDDEN, "That Minecraft account isn't linked to your Discord account.");
		return link;
	}

	private boolean slowDown(Context ctx, JsonObject in) {
		long now = System.currentTimeMillis();
		Long last = lastEdit.put(Web.str(in, "discordId"), now);
		if (last != null && now - last < 300) {
			error(ctx, HttpStatus.TOO_MANY_REQUESTS, "One change at a time, please.");
			return false;
		}
		return true;
	}

	private boolean running(Context ctx, String why) {
		if (server.state() == Supervisor.State.RUNNING) return true;
		error(ctx, HttpStatus.CONFLICT, "The server is offline. " + why);
		return false;
	}

	/** One action on the mod's website endpoint for this player (body may be null). */
	private JsonObject mod(String action, Discord.Link link, JsonObject body) throws InterruptedException {
		JsonObject b = body == null ? new JsonObject() : body;
		b.addProperty("action", action);
		// Only the player's uuid: "name" is a hub's new name here.
		b.addProperty("uuid", link.uuid().toLowerCase(Locale.ROOT));
		return dashboard.postForJson("/api/market/web", b, null);
	}

	private static boolean answered(Context ctx, JsonObject answer) {
		if (answer == null) {
			error(ctx, HttpStatus.SERVICE_UNAVAILABLE, "The server doesn't answer right now. Try again in a moment.");
			return false;
		}
		JsonElement e = answer.get("error");
		if (e != null) {
			error(ctx, HttpStatus.CONFLICT, e.getAsString());
			return false;
		}
		return true;
	}
}
