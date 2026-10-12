package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Performance;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * The public info page (/info/, optionally on its own host name INFO_HOST): the server's settings,
 * game rules, data pack settings and chosen config files, for players to read. Built from the mod's
 * dashboard while the server runs, and kept on disk so it stays readable while it's off. Passwords,
 * tokens and the RCON/management settings never show.
 */
final class InfoPage {
	static final class Settings {
		public boolean enabled = false;
		public String title = "Server info";
		/** A few lines on top of the page (plain text, blank lines start paragraphs). */
		public String intro = "";
		public boolean properties = true;
		public boolean gamerules = true;
		public boolean packs = true;
		/** The performance tweaks that are switched on (breeding caps, activation range…). */
		public boolean performance = true;
		/** server.properties keys left out besides the secret ones. */
		public List<String> hiddenProperties = new ArrayList<>();
		/** Config files (paths under config/) shown on the page. */
		public List<String> configs = new ArrayList<>();
	}

	/** Plain names for server.properties, so the page reads without knowing the keys. */
	private static final Map<String, String> LABELS = Map.ofEntries(
			Map.entry("difficulty", "Difficulty"), Map.entry("gamemode", "Game mode for new players"), Map.entry("hardcore", "Hardcore (banned on death)"),
			Map.entry("force-gamemode", "Game mode is reset when joining"), Map.entry("pvp", "Players can hurt each other"),
			Map.entry("allow-flight", "Flying allowed (for mods and elytra-like items)"), Map.entry("spawn-protection", "Protected area around spawn (blocks)"),
			Map.entry("max-players", "Player slots"), Map.entry("white-list", "Whitelist (only listed players can join)"), Map.entry("enforce-whitelist", "Kick players removed from the whitelist"),
			Map.entry("online-mode", "Only real Minecraft accounts"), Map.entry("enforce-secure-profile", "Signed chat required"),
			Map.entry("view-distance", "View distance (chunks)"), Map.entry("simulation-distance", "Simulation distance (chunks)"),
			Map.entry("level-name", "World name"), Map.entry("level-seed", "World seed"), Map.entry("level-type", "World type"), Map.entry("generate-structures", "Structures (villages, temples…)"),
			Map.entry("max-world-size", "World size limit (blocks from the center)"), Map.entry("allow-nether", "The Nether"),
			Map.entry("spawn-monsters", "Monsters spawn"), Map.entry("spawn-animals", "Animals spawn"), Map.entry("spawn-npcs", "Villagers spawn"),
			Map.entry("motd", "Server list message"), Map.entry("player-idle-timeout", "Kick after being idle (minutes, 0 = never)"),
			Map.entry("require-resource-pack", "Resource pack required"), Map.entry("resource-pack", "Resource pack download"), Map.entry("resource-pack-prompt", "Resource pack message"),
			Map.entry("server-port", "Port"), Map.entry("op-permission-level", "Operator permission level"), Map.entry("function-permission-level", "Function permission level"),
			Map.entry("entity-broadcast-range-percentage", "How far entities are visible (%)"), Map.entry("max-tick-time", "Watchdog (ms before a frozen server stops)"),
			Map.entry("network-compression-threshold", "Network compression from (bytes)"), Map.entry("rate-limit", "Packet limit per player"),
			Map.entry("hide-online-players", "Hide who's online in the server list"), Map.entry("enable-status", "Shown in the server list"),
			Map.entry("enable-command-block", "Command blocks"), Map.entry("broadcast-console-to-ops", "Console output shown to operators"),
			Map.entry("log-ips", "IP addresses in the log"), Map.entry("pause-when-empty-seconds", "Pause when empty (seconds)"),
			Map.entry("sync-chunk-writes", "Safe chunk saving"), Map.entry("max-chained-neighbor-updates", "Chained block update limit"),
			Map.entry("region-file-compression", "Region file compression"), Map.entry("accepts-transfers", "Accepts transfers from other servers"),
			Map.entry("bug-report-link", "Bug report link"), Map.entry("initial-enabled-packs", "Data packs on in new worlds"), Map.entry("initial-disabled-packs", "Data packs off in new worlds"));

	/** "spawn-protection" → "Spawn protection" for keys without a plain name. */
	static String humanize(String key) {
		String t = key.replaceAll("[-_.]+", " ").replaceAll("([a-z])([A-Z])", "$1 $2").trim().toLowerCase(java.util.Locale.ROOT);
		return t.isEmpty() ? key : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	/** Keys whose values are hidden in published config files. */
	private static final Pattern SECRET_KEY = Pattern.compile("(?i)(token|secret|passw|webhook|api[_-]?key|private[_-]?key|credential|auth)");
	private static final Pattern JSON_SECRET = Pattern.compile("(\"[^\"]*(?i:token|secret|passw|webhook|api[_-]?key|private[_-]?key|credential|auth)[^\"]*\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,\\s}\\]]+)");
	private static final Pattern LINE_SECRET = Pattern.compile("(?m)^(\\s*[A-Za-z0-9_.\\-\"]*(?i:token|secret|passw|webhook|api[_-]?key|private[_-]?key|credential|auth)[A-Za-z0-9_.\\-\"]*\\s*[=:]\\s*)(.+)$");
	private static final long MAX_CONFIG = 256 * 1024;

	private final Path settingsFile;
	private final Path cacheFile;
	private final Path serverDir;
	private final Supervisor server;
	private final DashboardProxy mod;
	private final Audit audit;
	private final String host;
	private volatile Settings settings;
	private volatile JsonObject live;
	private volatile long liveAt;

	private Auth auth;

	/** Admins may look at the page before it's public. */
	void useAuth(Auth auth) {
		this.auth = auth;
	}

	InfoPage(Path dataDir, Path serverDir, Supervisor server, DashboardProxy mod, Audit audit) {
		this.settingsFile = dataDir.resolve("info.json");
		this.cacheFile = dataDir.resolve("info-cache.json");
		this.serverDir = serverDir;
		this.server = server;
		this.mod = mod;
		this.audit = audit;
		this.host = Panel.env("INFO_HOST", "").toLowerCase();
		this.settings = readSettings();
	}

	private Settings readSettings() {
		try {
			if (Files.exists(settingsFile)) {
				Settings s = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
				if (s != null) {
					if (s.hiddenProperties == null) s.hiddenProperties = new ArrayList<>();
					if (s.configs == null) s.configs = new ArrayList<>();
					return s;
				}
			}
		} catch (IOException | RuntimeException e) {
			// Defaults.
		}
		return new Settings();
	}

	/** On the info page's own host name only the page exists; register before everything else. */
	void registerHostFilter(JavalinDefaultRoutingApi routes) {
		if (host.isEmpty()) return;
		routes.before(ctx -> {
			String h = ctx.header("Host");
			if (h == null || !h.toLowerCase().replaceAll(":\\d+$", "").equals(host)) return;
			String path = ctx.path();
			if (path.equals("/")) {
				ctx.redirect("/info/");
				ctx.skipRemainingHandlers();
			} else if (!path.startsWith("/info/") && !path.equals("/app.css") && !path.startsWith("/img/") && !path.equals("/branding/panel")) {
				ctx.status(HttpStatus.NOT_FOUND).result("Not found");
				ctx.skipRemainingHandlers();
			}
		});
	}

	// ── Building the page ─────────────────────────────────────────────────

	/** What the mod knows (settings with descriptions, game rules, pack settings), refreshed at most once a minute. */
	private synchronized JsonObject fromMod() {
		long now = System.currentTimeMillis();
		if (live != null && now - liveAt < 60_000) return live;
		if (server.running()) {
			JsonObject props = mod.get("/api/properties");
			JsonObject rules = mod.get("/api/gamerules");
			JsonObject packs = mod.get("/api/packsettings");
			if (props != null || rules != null || packs != null) {
				JsonObject fresh = new JsonObject();
				if (props != null) fresh.add("properties", props);
				if (rules != null) fresh.add("gamerules", rules);
				if (packs != null) fresh.add("packs", packs);
				fresh.addProperty("at", now);
				live = fresh;
				liveAt = now;
				try {
					Files.writeString(cacheFile, fresh.toString(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					// Kept in memory.
				}
				return fresh;
			}
		}
		if (live == null) {
			try {
				if (Files.exists(cacheFile)) live = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				// Nothing cached yet.
			}
			liveAt = now;
		}
		return live != null ? live : new JsonObject();
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	Map<String, Object> page() {
		Settings s = settings;
		JsonObject src = fromMod();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("title", s.title);
		out.put("intro", s.intro);
		String motd = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("motd", "");
		if (!motd.isBlank()) out.put("motd", motd);
		if (src.has("at")) out.put("updated", src.get("at").getAsLong());
		if (s.properties) out.put("properties", properties(src, s));
		if (s.gamerules && src.has("gamerules")) {
			List<Map<String, Object>> rules = new ArrayList<>();
			for (JsonElement e : src.getAsJsonObject("gamerules").getAsJsonArray("rules")) {
				JsonObject r = e.getAsJsonObject();
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", str(r, "name"));
				m.put("id", str(r, "id"));
				m.put("category", str(r, "category"));
				m.put("description", str(r, "description"));
				m.put("value", str(r, "value"));
				m.put("changed", !str(r, "value").equals(str(r, "defaultValue")));
				rules.add(m);
			}
			out.put("gamerules", rules);
		}
		if (s.packs && src.has("packs")) out.put("packs", packs(src.getAsJsonObject("packs")));
		if (s.performance) out.put("performance", performance(Performance.read(serverDir.resolve("config").resolve(Performance.FILE))));
		List<Map<String, Object>> configs = new ArrayList<>();
		for (String rel : s.configs) {
			String text = configText(rel);
			if (text != null) configs.add(Map.of("path", rel, "text", text));
		}
		out.put("configs", configs);
		return out;
	}

	/** server.properties as players may see it: the mod's descriptions when known, never secrets or RCON/management. */
	private List<Map<String, Object>> properties(JsonObject src, Settings s) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (src.has("properties")) {
			for (JsonElement e : src.getAsJsonObject("properties").getAsJsonArray("fields")) {
				JsonObject f = e.getAsJsonObject();
				String key = str(f, "key");
				String group = str(f, "group");
				if ((f.has("secret") && f.get("secret").getAsBoolean()) || hiddenKey(key, group, s)) continue;
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("key", key);
				m.put("label", LABELS.getOrDefault(key, humanize(key)));
				m.put("group", group);
				m.put("description", str(f, "description"));
				m.put("value", str(f, "value"));
				out.add(m);
			}
			return out;
		}
		// Never ran with the mod yet: the file itself, without descriptions.
		ServerTuning.readProperties(serverDir.resolve("server.properties")).forEach((k, v) -> {
			String key = k.toString();
			if (hiddenKey(key, "", s)) return;
			out.add(new LinkedHashMap<>(Map.of("key", key, "label", LABELS.getOrDefault(key, humanize(key)), "group", "Settings", "description", "", "value", v.toString())));
		});
		out.sort((a, b) -> a.get("key").toString().compareTo(b.get("key").toString()));
		return out;
	}

	private static boolean hiddenKey(String key, String group, Settings s) {
		return group.startsWith("Management") || key.startsWith("rcon.") || key.startsWith("management-server") || key.startsWith("query.")
				|| key.equals("enable-rcon") || key.equals("server-ip") || SECRET_KEY.matcher(key).find() || s.hiddenProperties.contains(key);
	}

	private static Map<String, Object> tweak(String id, String title, String icon, String desc, List<Map<String, String>> rows) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("title", title);
		m.put("icon", icon);
		m.put("description", desc);
		m.put("rows", rows);
		return m;
	}

	private static Map<String, String> line(String label, String value, String desc) {
		return Map.of("label", label, "value", value, "description", desc);
	}

	private static String blocks(double v) {
		return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + (v == 1 ? " block" : " blocks");
	}

	/** The switched-on performance tweaks, explained for players (what changes for them, compared to vanilla). */
	static List<Map<String, Object>> performance(Performance.Settings p) {
		List<Map<String, Object>> out = new ArrayList<>();
		Performance.Dynamic d = p.dynamic;
		if (d.enabled) {
			List<Map<String, String>> rows = new ArrayList<>();
			rows.add(line("Starts lowering at", d.targetMspt + " ms per tick", "A tick should take at most 50 ms; above this the server starts saving work."));
			rows.add(line("Back to normal below", d.recoverMspt + " ms per tick", "After a minute below this, everything goes back up step by step."));
			rows.add(line("Lowest simulation distance", d.simulationMin + " chunks", "How far around players crops grow, mobs move and redstone runs."));
			rows.add(line("Lowest mob cap", d.mobCapMin + " %", "Fewer mobs spawn naturally while it's lowered (100 % = vanilla)."));
			rows.add(line("View distance lowered too", d.view ? "true" : "false", d.view ? "As a last step, down to " + d.viewMin + " chunks." : "How far you can see always stays the same."));
			out.add(tweak("dynamic", "Lag protection", "bi-activity",
					"When the server can't keep up, it lowers a few distances one step at a time instead of lagging, and raises them again once it's fine.", rows));
		}
		Performance.Activation a = p.activation;
		if (a.enabled) {
			String tick = "Farther away they move and think every " + a.every + " ticks instead of every tick.";
			List<Map<String, String>> rows = new ArrayList<>();
			rows.add(line("Monsters", blocks(a.monsters), "Zombies, skeletons, creepers…"));
			rows.add(line("Animals", blocks(a.animals), "Cows, sheep, chickens…"));
			rows.add(line("Villagers", blocks(a.villagers), ""));
			rows.add(line("Everything else", blocks(a.other), ""));
			out.add(tweak("activation", "Mobs far from players", "bi-broadcast",
					"Mobs that are far from every player are updated less often. " + tick
							+ " Items, projectiles, vehicles, bosses, mobs on a lead and mobs that were just hurt always update normally.", rows));
		}
		Performance.Breeding b = p.breeding;
		if (b.enabled) {
			out.add(tweak("breeding", "Breeding limits", "bi-heart",
					"Animals and villagers can't breed while too many of their kind live close together. Spread big farms out to keep breeding.",
					List.of(line("Animals", "up to " + b.animals + " per kind", "Within " + blocks(b.radius) + "; feeding them does nothing above this."),
							line("Villagers", "up to " + b.villagers + " villagers", "Within " + blocks(b.radius) + "."))));
		}
		Performance.Merge m = p.merge;
		if (m.enabled) {
			out.add(tweak("merge", "Item and XP merging", "bi-intersect",
					"Dropped items of the same kind and experience orbs merge from farther away, so fewer of them lie around.",
					List.of(line("Dropped items", "within " + blocks(m.items), "Vanilla: 0.5 blocks."),
							line("Experience orbs", "within " + blocks(m.xp), "The XP isn't lost, it's added together."))));
		}
		if (p.villagers.lobotomize) {
			out.add(tweak("villagers", "Villagers in trading halls", "bi-shop",
					"Villagers that can't move (in 1×1 trading cells) think less often. Trading and restocking still work.",
					List.of(line("They think every", p.villagers.every + " ticks", "Vanilla: every tick (20 times a second)."))));
		}
		return out;
	}

	/** Pack settings as label → shown value (the option's label for choices). */
	private static List<Map<String, Object>> packs(JsonObject packs) {
		Map<String, List<Map<String, Object>>> byPack = new LinkedHashMap<>();
		for (JsonElement e : packs.getAsJsonArray("dialogs")) {
			JsonObject d = e.getAsJsonObject();
			List<Map<String, Object>> settings = new ArrayList<>();
			for (JsonElement se : d.getAsJsonArray("settings")) {
				JsonObject st = se.getAsJsonObject();
				String value = str(st, "value");
				if (st.has("options") && st.get("options").isJsonArray()) {
					for (JsonElement oe : st.getAsJsonArray("options")) {
						JsonObject opt = oe.getAsJsonObject();
						if (str(opt, "id").equals(value)) value = str(opt, "label");
					}
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("label", str(st, "label"));
				m.put("type", str(st, "type"));
				m.put("value", value);
				settings.add(m);
			}
			Map<String, Object> dialog = new LinkedHashMap<>();
			dialog.put("id", str(d, "id"));
			dialog.put("title", str(d, "title"));
			dialog.put("section", str(d, "section"));
			dialog.put("settings", settings);
			byPack.computeIfAbsent(str(d, "pack"), k -> new ArrayList<>()).add(dialog);
		}
		// The packs' own menus (as in game), so the page can be navigated the same way.
		Map<String, JsonElement> trees = new LinkedHashMap<>();
		if (packs.has("tree") && packs.get("tree").isJsonArray()) {
			for (JsonElement t : packs.getAsJsonArray("tree")) trees.put(str(t.getAsJsonObject(), "pack"), t.getAsJsonObject().get("children"));
		}
		List<Map<String, Object>> out = new ArrayList<>();
		byPack.forEach((pack, dialogs) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("pack", pack);
			m.put("dialogs", dialogs);
			if (trees.containsKey(pack)) m.put("menu", trees.get(pack));
			out.add(m);
		});
		return out;
	}

	/** A published config file with passwords, tokens and keys blanked out; null if it can't be shown. */
	private String configText(String rel) {
		Path config = serverDir.resolve("config").normalize();
		Path f = serverDir.resolve(rel).normalize();
		try {
			if (!f.startsWith(config) || !Files.isRegularFile(f) || Files.size(f) > MAX_CONFIG) return null;
			String text = Files.readString(f, StandardCharsets.UTF_8);
			return mask(text, f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".*\\.json[c5]?$"));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** JSON keeps valid JSON (the page shows it as a list); other formats are masked line by line. */
	static String mask(String text, boolean json) {
		return json ? JSON_SECRET.matcher(text).replaceAll("$1\"••••••\"") : LINE_SECRET.matcher(text).replaceAll("$1••••••");
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/info/data.json", ctx -> {
			Auth.Session session = auth == null ? null : auth.session(ctx.cookie(Auth.COOKIE));
			boolean preview = session != null && session.can(Permissions.SETTINGS_SERVER);
			if (!settings.enabled && !preview) {
				ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "The info page is switched off."));
				return;
			}
			ctx.header("Cache-Control", "no-cache");
			json(ctx, page());
		});
		routes.get("/api/info", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", settings);
			out.put("host", host);
			JsonObject src = fromMod();
			List<String> keys = new ArrayList<>();
			if (src.has("properties")) {
				for (JsonElement e : src.getAsJsonObject("properties").getAsJsonArray("fields")) {
					JsonObject f = e.getAsJsonObject();
					if (!(f.has("secret") && f.get("secret").getAsBoolean()) && !hiddenKey(str(f, "key"), str(f, "group"), new Settings())) keys.add(str(f, "key"));
				}
			}
			out.put("propertyKeys", keys);
			json(ctx, out);
		}));
		routes.post("/api/info", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null || next.title == null || next.title.length() > 80 || (next.intro != null && next.intro.length() > 4000)) {
				error(ctx, HttpStatus.BAD_REQUEST, "The title can be up to 80 characters, the intro up to 4000.");
				return;
			}
			if (next.intro == null) next.intro = "";
			if (next.hiddenProperties == null) next.hiddenProperties = new ArrayList<>();
			if (next.configs == null) next.configs = new ArrayList<>();
			Path config = serverDir.resolve("config").normalize();
			for (String rel : next.configs) {
				if (!serverDir.resolve(rel).normalize().startsWith(config)) {
					error(ctx, HttpStatus.BAD_REQUEST, "Only files in config/ can be published.");
					return;
				}
			}
			Files.writeString(settingsFile, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(next));
			settings = next;
			audit.log(me.name(), "info.settings", (next.enabled ? "on" : "off") + ", " + next.configs.size() + " config files", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
