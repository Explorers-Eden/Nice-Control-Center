package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Discord DMs to linked players about their own things: what they sold, items waiting in their market mailbox,
 * a claim that expires within a week, a message from an admin. Each player switches the kinds on or off on the
 * website (default on); admins switch all of it off in the Discord settings. Checked every minute while the
 * server runs; what's already been told is remembered in /data/panel/notices.json.
 */
final class PlayerNotices {
	static final List<String> KINDS = List.of("sales", "mail", "claims", "messages");
	private static final long SALES_EVERY = 5 * 60_000L;
	private static final long MAIL_EVERY = 60 * 60_000L;
	private static final long DAY = 86_400_000L;

	/** What's kept between restarts. */
	static final class State {
		/** uuid → kind → on (missing = on). */
		Map<String, Map<String, Boolean>> prefs = new HashMap<>();
		/** Market revision whose sales are told; the mail parcel counts at that point. */
		long marketRev = -1;
		String marketWorld;
		Map<String, Integer> parcels = new HashMap<>();
		/** claim id → expiry day already told. */
		Map<String, Long> claimsTold = new HashMap<>();
		Map<String, Long> lastMail = new HashMap<>();
	}

	private final Path file;
	private final Discord discord;
	private final DashboardProxy dashboard;
	private final ClaimService claims;
	private final MarketService market;
	private final Supervisor server;
	private State state;
	/** seller uuid → sale lines waiting to be bundled, and when the first came. */
	private final Map<String, List<String>> pendingSales = new ConcurrentHashMap<>();
	private final Map<String, Long> pendingSince = new ConcurrentHashMap<>();
	private final Map<String, Long> pendingTotal = new ConcurrentHashMap<>();
	private long lastClaimCheck;

	PlayerNotices(Path dataDir, Discord discord, DashboardProxy dashboard, ClaimService claims, MarketService market, Supervisor server) {
		this.file = dataDir.resolve("notices.json");
		this.discord = discord;
		this.dashboard = dashboard;
		this.claims = claims;
		this.market = market;
		this.server = server;
		State s = null;
		try {
			if (Files.exists(file)) s = Json.GSON.fromJson(Files.readString(file), State.class);
		} catch (IOException | RuntimeException e) {
			System.err.println("Player notices: " + e);
		}
		state = s == null ? new State() : s;
	}

	void start() {
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "player-notices");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(this::check, 90, 60, TimeUnit.SECONDS);
	}

	// ---------------------------------------------------------------- the player's switches

	synchronized Map<String, Boolean> prefs(String uuid) {
		Map<String, Boolean> saved = state.prefs.getOrDefault(uuid.toLowerCase(Locale.ROOT), Map.of());
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (String k : KINDS) out.put(k, saved.getOrDefault(k, true));
		return out;
	}

	synchronized void setPref(String uuid, String kind, boolean on) {
		if (!KINDS.contains(kind)) return;
		state.prefs.computeIfAbsent(uuid.toLowerCase(Locale.ROOT), k -> new HashMap<>()).put(kind, on);
		save();
	}

	/** DMs are possible at all: the bridge runs and admins allow them. */
	boolean available() {
		return discord.settings().enabled && discord.settings().playerDms;
	}

	// ---------------------------------------------------------------- sending

	/** A DM to the player's linked Discord account, when they want this kind. */
	private void tell(String uuid, String kind, String text) {
		if (!available() || !prefs(uuid).getOrDefault(kind, true)) return;
		String id = uuid.toLowerCase(Locale.ROOT);
		for (Discord.Link l : discord.links()) {
			if (!l.uuid().equalsIgnoreCase(id)) continue;
			System.out.println("[notice] " + l.name() + " (" + kind + "): " + text.replace('\n', ' '));
			discord.dm(l.discordId(), text);
			return;
		}
	}

	/** An admin wrote to the player from the panel (they answer in game). */
	void adminMessage(String uuid, String from, String text) {
		tell(uuid, "messages", "**" + from + "** (admin) wrote to you on the server:\n> " + text.replace("\n", "\n> ")
				+ "\nAnswer in game with `/nccreply <message>`.");
	}

	// ---------------------------------------------------------------- the minute check

	private void check() {
		try {
			if (!available() || server.state() != Supervisor.State.RUNNING) return;
			checkMarket();
			flushSales(false);
			if (System.currentTimeMillis() - lastClaimCheck > 60 * 60_000L) {
				lastClaimCheck = System.currentTimeMillis();
				checkClaims();
			}
		} catch (RuntimeException | IOException | InterruptedException e) {
			System.err.println("Player notices: " + e);
		}
	}

	private synchronized void checkMarket() throws InterruptedException {
		JsonObject answer = dashboard.get("/api/market/changes?since=" + Math.max(0, state.marketRev));
		if (answer == null) return;
		MarketService.Changes c = Json.GSON.fromJson(answer, MarketService.Changes.class);
		boolean first = state.marketRev < 0 || !c.worldId.equals(state.marketWorld) || c.full;
		String currency = currencyName();
		if (!first) {
			for (Market.Sale s : c.sales) {
				if (s.seller == null || s.rev <= state.marketRev) continue;
				pendingSales.computeIfAbsent(s.seller, k -> new ArrayList<>()).add(String.format(Locale.ROOT, "%,d × %s to %s (+%,d %s)",
						s.amount, s.itemName, s.buyerName == null ? "someone" : s.buyerName, s.total, currency));
				pendingSince.putIfAbsent(s.seller, System.currentTimeMillis());
				pendingTotal.merge(s.seller, (long) s.total, Long::sum);
			}
		}
		// Parcels: tell when a mailbox has more than at the last look (at most once an hour each).
		Map<String, Integer> now = new HashMap<>();
		if (c.mail != null) c.mail.forEach((uuid, m) -> now.put(uuid, m.parcels.stream().mapToInt(p -> p.amount > 0 ? 1 : 0).sum() + m.orders.size()));
		if (!first) {
			long t = System.currentTimeMillis();
			now.forEach((uuid, n) -> {
				int before = state.parcels.getOrDefault(uuid, 0);
				if (n > before && t - state.lastMail.getOrDefault(uuid, 0L) > MAIL_EVERY) {
					state.lastMail.put(uuid, t);
					tell(uuid, "mail", n == 1 ? "Something waits for you in your market mailbox. Collect it in game with `/market` → Mailbox."
							: n + " things wait for you in your market mailbox. Collect them in game with `/market` → Mailbox.");
				}
			});
		}
		state.parcels = now;
		state.marketRev = c.rev;
		state.marketWorld = c.worldId;
		save();
	}

	/** "diamonds" for minecraft:diamond. */
	private String currencyName() {
		String id = market.settings().currency == null ? "minecraft:diamond" : market.settings().currency;
		return id.replaceAll("^[^:]*:", "").replace('_', ' ') + "s";
	}

	/** Sends the bundled sales of each seller whose first sale is old enough (or all of them). */
	private void flushSales(boolean all) {
		long t = System.currentTimeMillis();
		for (String seller : List.copyOf(pendingSales.keySet())) {
			Long since = pendingSince.get(seller);
			if (!all && since != null && t - since < SALES_EVERY) continue;
			List<String> lines = pendingSales.remove(seller);
			pendingSince.remove(seller);
			Long total = pendingTotal.remove(seller);
			if (lines == null || lines.isEmpty()) continue;
			String body = lines.size() == 1 ? "You sold " + lines.get(0) + " on the market."
					: "You sold on the market:\n• " + String.join("\n• ", lines.subList(0, Math.min(10, lines.size())))
							+ (lines.size() > 10 ? "\n…and " + (lines.size() - 10) + " more" : "");
			tell(seller, "sales", body + "\nThe money is in your bank" + (total != null && lines.size() > 1 ? String.format(Locale.ROOT, " (%,d in all)", total) : "") + ".");
		}
	}

	private synchronized void checkClaims() throws IOException {
		Claims.Settings s = claims.settings();
		if (!s.enabled || s.inactiveDays <= 0) return;
		ClaimService.Changes now = claims.current();
		Claims.Data data = new Claims.Data();
		for (Claims.Claim c : now.claims) data.claims.put(c.id, c);
		data.players = now.players;
		long t = System.currentTimeMillis();
		DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withZone(ZoneId.systemDefault());
		boolean changed = false;
		for (Claims.Claim c : now.claims) {
			long expires = data.expiresAt(c, s);
			if (expires <= 0 || expires - t > 7 * DAY || expires < t) continue;
			long dayOf = expires / DAY;
			if (Long.valueOf(dayOf).equals(state.claimsTold.get(c.id))) continue;
			state.claimsTold.put(c.id, dayOf);
			changed = true;
			tell(c.owner, "claims", "Your claim **" + c.displayName() + "** expires on " + day.format(java.time.Instant.ofEpochMilli(expires))
					+ " because you haven't played for a while. Join the server before then to keep it.");
		}
		state.claimsTold.keySet().removeIf(id -> now.claims.stream().noneMatch(c -> c.id.equals(id)));
		if (changed) save();
	}

	private synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(state));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.err.println("Player notices: " + e);
		}
	}
}
