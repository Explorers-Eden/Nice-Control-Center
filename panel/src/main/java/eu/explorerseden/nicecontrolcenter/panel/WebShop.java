package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The market for the website (explorerseden.eu): a logged-in player's bank balance and buying from it.
 * The website logs players in with Discord and calls these from its own server with a shared token
 * (WEBSITE_TOKEN); the Discord account is turned into the player(s) it's linked to here. Under /info/ so
 * they work on the info page's host name too. Purchases go through the running mod only.
 */
final class WebShop {
	private static final Set<String> KINDS = Set.of("listing", "claims", "command", "pet");

	private final byte[] token;
	private final MarketService market;
	private final Discord discord;
	private final DashboardProxy dashboard;
	private final Supervisor server;
	private final Audit audit;
	/** discordId → last purchase (ms), one per second at most. */
	private final Map<String, Long> lastBuy = new ConcurrentHashMap<>();

	WebShop(String token, MarketService market, Discord discord, DashboardProxy dashboard, Supervisor server, Audit audit) {
		this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
		this.market = market;
		this.discord = discord;
		this.dashboard = dashboard;
		this.server = server;
		this.audit = audit;
	}

	void register(RoutesConfig routes) {
		routes.post("/info/web/account", ctx -> {
			JsonObject in = authorized(ctx);
			if (in == null) return;
			List<Discord.Link> links = linksOf(Web.str(in, "discordId"));
			boolean running = server.state() == Supervisor.State.RUNNING;
			Market.Data offline = running ? null : market.worldData();
			List<Map<String, Object>> accounts = new ArrayList<>();
			for (Discord.Link l : links) {
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("uuid", l.uuid());
				a.put("name", l.name());
				if (running) {
					JsonObject b = new JsonObject();
					b.addProperty("action", "account");
					b.addProperty("uuid", l.uuid());
					JsonObject answer = dashboard.postForJson("/api/market/web", b, null);
					if (answer != null && !answer.has("error")) answer.entrySet().forEach(e -> a.put(e.getKey(), e.getValue()));
				} else if (offline != null) {
					// While the server is off: what the world file says (buying waits for the server).
					Market.Mailbox m = offline.mail.get(l.uuid());
					a.put("balance", m == null ? 0 : m.balance);
					a.put("parcels", m == null ? List.of() : m.parcels.stream().map(p -> Map.of("name", p.item.name, "item", p.item.id, "amount", p.amount)).toList());
					a.put("orders", m == null ? List.of() : m.orders.stream().map(o -> Map.of("name", o.name, "times", o.times, "bought", o.bought)).toList());
					a.put("online", false);
				}
				accounts.add(a);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("running", running);
			out.put("currency", market.settings().currency);
			out.put("accounts", accounts);
			json(ctx, out);
		});
		routes.post("/info/web/buy", ctx -> {
			JsonObject in = authorized(ctx);
			if (in == null) return;
			String discordId = Web.str(in, "discordId");
			String uuid = Web.str(in, "uuid").toLowerCase(java.util.Locale.ROOT);
			Discord.Link link = linksOf(discordId).stream().filter(l -> l.uuid().equalsIgnoreCase(uuid)).findFirst().orElse(null);
			if (link == null) {
				error(ctx, HttpStatus.FORBIDDEN, "That Minecraft account isn't linked to your Discord account.");
				return;
			}
			String kind = Web.str(in, "kind");
			if (!KINDS.contains(kind)) {
				error(ctx, HttpStatus.BAD_REQUEST, "What should be bought?");
				return;
			}
			if (server.state() != Supervisor.State.RUNNING) {
				error(ctx, HttpStatus.CONFLICT, "The server is offline. Buying works while it runs.");
				return;
			}
			long now = System.currentTimeMillis();
			Long last = lastBuy.put(discordId, now);
			if (last != null && now - last < 1000) {
				error(ctx, HttpStatus.TOO_MANY_REQUESTS, "One purchase at a time, please.");
				return;
			}
			JsonObject b = new JsonObject();
			b.addProperty("action", "buy");
			b.addProperty("uuid", uuid);
			b.addProperty("name", link.name());
			b.addProperty("kind", kind);
			for (String k : List.of("id", "amount", "price", "per")) b.addProperty(k, Web.str(in, k));
			JsonObject answer = dashboard.postForJson("/api/market/web", b, null);
			if (answer == null) {
				error(ctx, HttpStatus.SERVICE_UNAVAILABLE, "The server doesn't answer right now. Try again in a moment.");
				return;
			}
			if (answer.has("error")) {
				error(ctx, HttpStatus.CONFLICT, answer.get("error").getAsString());
				return;
			}
			audit.log("website", "market.webbuy", link.name() + ": " + Web.str(answer, "message"), Web.clientIp(ctx));
			market.syncSoon();
			json(ctx, answer);
		});
	}

	/** The request body when the website's token is right; otherwise answered (404 while no token is set) and null. */
	private JsonObject authorized(Context ctx) {
		ctx.header("Cache-Control", "no-store");
		if (token.length == 0) {
			ctx.status(HttpStatus.NOT_FOUND).result("Not found");
			return null;
		}
		String h = ctx.header("Authorization");
		byte[] given = h != null && h.startsWith("Bearer ") ? h.substring(7).strip().getBytes(StandardCharsets.UTF_8) : new byte[0];
		if (!MessageDigest.isEqual(given, token)) {
			error(ctx, HttpStatus.UNAUTHORIZED, "Wrong token.");
			return null;
		}
		JsonObject in = Web.body(ctx);
		if (in == null || Web.str(in, "discordId").isEmpty()) {
			error(ctx, HttpStatus.BAD_REQUEST, "No Discord account.");
			return null;
		}
		return in;
	}

	private List<Discord.Link> linksOf(String discordId) {
		if (discordId.isEmpty()) return List.of();
		return discord.links().stream().filter(l -> discordId.equals(l.discordId())).toList();
	}
}
