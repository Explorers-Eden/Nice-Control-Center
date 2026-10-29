package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Points;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Points and reward shops (Games → Points, Reward shops): the shops (config file), balances (from the mod, or the world's file when it's off), give and take. */
final class PointsRoutes {
	private final Path serverDir;
	private final ServerFiles files;
	private final DashboardProxy dashboard;
	private final Audit audit;

	PointsRoutes(Path serverDir, ServerFiles files, DashboardProxy dashboard, Audit audit) {
		this.serverDir = serverDir;
		this.files = files;
		this.dashboard = dashboard;
		this.audit = audit;
	}

	private Path file() {
		return serverDir.resolve("config").resolve(Points.FILE);
	}

	/** The balances from the world's file, shaped like the mod's answer. */
	private Map<String, Object> offline() {
		Points.Data d = Points.readData(serverDir.resolve(files.worldFolder()).resolve(Points.DATA));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<String, Points.Account> e : d.accounts.entrySet()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("uuid", e.getKey());
			r.put("name", e.getValue().name);
			r.put("balance", e.getValue().balance);
			r.put("earned", e.getValue().earned);
			List<Points.Change> h = new ArrayList<>(e.getValue().history);
			Collections.reverse(h);
			r.put("history", h.size() > 10 ? h.subList(0, 10) : h);
			rows.add(r);
		}
		rows.sort((x, y) -> Long.compare((long) y.get("balance"), (long) x.get("balance")));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("accounts", rows);
		m.put("sold", d.sold);
		return m;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/points", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Points.Settings s = Points.read(file());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", s);
			// The shops' skins as hashes, for the face pictures (info/heads/<hash>).
			Map<String, String> skins = new LinkedHashMap<>();
			for (Points.Shop shop : s.shops) {
				String hash = Pets.skinHash(Pets.texture(shop.texture));
				if (hash != null) skins.put(shop.id, hash);
				for (Points.Entry e : shop.entries) {
					String icon = Pets.skinHash(Pets.texture(e.iconTexture));
					if (icon != null) skins.put(e.id, icon);
				}
			}
			out.put("skins", skins);
			// For the rewards' "other rewards" rows (same as in Rewards rules).
			out.put("actions", eu.explorerseden.nicecontrolcenter.Rewards.ACTIONS);
			out.put("pets", Pets.read(serverDir.resolve("config").resolve(Pets.FILE)).pets.stream().map(p -> p.name).toList());
			JsonElement live = null;
			try {
				live = dashboard.getElement("/api/points");
			} catch (Exception e) {
				live = null;
			}
			out.put("live", live != null && live.isJsonObject());
			out.put("balances", live != null && live.isJsonObject() ? live : offline());
			json(ctx, out);
		}));
		routes.post("/api/points", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Points.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Points.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Points.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			// Set only in game (NPC spots are placed there, items come from the hand): kept as the mod wrote them,
			// except that the page may take some out.
			Points.Settings now = Points.read(file());
			for (Points.Shop shop : next.shops) {
				Points.Shop was = now.shops.stream().filter(x -> x.id.equals(shop.id)).findFirst().orElse(null);
				if (was == null) {
					shop.npcs = new ArrayList<>();
					for (Points.Entry e : shop.entries) e.items = new ArrayList<>();
					continue;
				}
				List<String> keep = shop.npcs.stream().map(PointsRoutes::spot).toList();
				shop.npcs = new ArrayList<>(was.npcs.stream().filter(p -> keep.contains(spot(p))).toList());
				for (Points.Entry e : shop.entries) {
					Points.Entry old = was.entry(e.id);
					if (old == null) {
						e.items = new ArrayList<>();
						continue;
					}
					List<String> kept = e.items.stream().map(i -> i.item == null ? "" : i.item.stack).toList();
					List<Points.Stack> items = new ArrayList<>();
					for (Points.Stack st : old.items) {
						if (!kept.contains(st.item.stack)) continue;
						// The count may be changed on the page.
						Points.Stack mine = e.items.stream().filter(i -> i.item != null && st.item.stack.equals(i.item.stack)).findFirst().orElse(st);
						st.count = Math.max(1, Math.min(6400, mine.count));
						items.add(st);
					}
					e.items = items;
				}
			}
			problem = Points.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Points.write(file(), next);
			audit.log(me.name(), "points.settings", next.shops.size() + " reward shops, " + next.shops.stream().mapToInt(x -> x.entries.size()).sum() + " rewards", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/points/adjust", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			String player = str(body, "player").strip();
			long amount;
			try {
				amount = body.has("amount") ? body.get("amount").getAsLong() : 0;
			} catch (RuntimeException e) {
				amount = 0;
			}
			if (player.isEmpty() || amount == 0) {
				error(ctx, HttpStatus.BAD_REQUEST, "Who, and how many?");
				return;
			}
			JsonObject send = new JsonObject();
			send.addProperty("player", player);
			send.addProperty("amount", amount);
			String reason = str(body, "reason").strip();
			send.addProperty("reason", reason.isEmpty() ? "from " + me.name() + " (panel)" : reason);
			JsonObject answer = dashboard.postForJson("/api/points/adjust", send, me.name());
			if (answer == null) {
				error(ctx, HttpStatus.CONFLICT, "Points can be given and taken while the server runs.");
				return;
			}
			if (answer.has("error")) {
				error(ctx, HttpStatus.BAD_REQUEST, answer.get("error").getAsString());
				return;
			}
			audit.log(me.name(), "points.adjust", (amount > 0 ? "gave " : "took ") + Math.abs(amount) + " points " + (amount > 0 ? "to " : "from ") + str(answer, "name") + (reason.isEmpty() ? "" : " (" + reason + ")"), clientIp(ctx));
			json(ctx, answer);
		}));
	}

	private static String spot(eu.explorerseden.nicecontrolcenter.Games.Pos p) {
		return p.dim + "|" + Math.round(p.x * 2) + "|" + Math.round(p.y * 2) + "|" + Math.round(p.z * 2);
	}
}
