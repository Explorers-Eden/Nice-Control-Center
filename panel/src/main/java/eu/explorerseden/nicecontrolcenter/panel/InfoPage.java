package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Links;
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
		/** The commands every player may use, with descriptions. */
		public boolean commands = true;
		/** How land claims work here (only while claims are on). */
		public boolean claims = true;
		/** The permanent area and the world's edge per dimension. */
		public boolean borders = true;
		/** The switched-on gameplay tweaks (tree felling, anvil, portals…). */
		public boolean tweaks = true;
		/** The player market's listings (while the market is open). */
		public boolean market = true;
		/** When the next scheduled restart is, in the banner. */
		public boolean restart = true;
		/** server.properties keys left out besides the secret ones. */
		public List<String> hiddenProperties = new ArrayList<>();
		/** Config files (paths under config/) shown on the page. */
		public List<String> configs = new ArrayList<>();
		/** The page's address for /info in the game; empty works it out (INFO_HOST or PANEL_PUBLIC_URL). */
		public String infoAddress = "";
		/** Before 1.12.5 the website had its own field; it's moved into {@link #links} as /website when read. */
		public String website;
		/** What /info says before the link, and the link's text. */
		public String infoText = "Everything about how this server works:";
		public String infoLabel = "Server info";
		/** Own link commands for the game (like /website or /discord). */
		public List<Links.Custom> links = new ArrayList<>();
	}

	/** The info page's address players get with /info, or empty. */
	String publicAddress() {
		Settings s = settings;
		return s.infoAddress != null && !s.infoAddress.isBlank() ? s.infoAddress : autoAddress();
	}

	/** The address worked out from INFO_HOST or PANEL_PUBLIC_URL, or empty. */
	String autoAddress() {
		if (!host.isEmpty()) return "https://" + host + "/";
		String panel = Panel.env("PANEL_PUBLIC_URL", "").strip().replaceFirst("/+$", "");
		return panel.isEmpty() ? "" : panel + "/info/";
	}

	/** The links for /info and the own link commands, in the server's config folder for the mod. */
	void writeLinks() {
		Links.Settings l = new Links.Settings();
		l.info = publicAddress();
		l.infoOn = settings.enabled;
		l.infoText = settings.infoText == null ? "" : settings.infoText;
		l.infoLabel = settings.infoLabel == null || settings.infoLabel.isBlank() ? "Server info" : settings.infoLabel;
		l.custom = settings.links == null ? new ArrayList<>() : settings.links;
		try {
			Links.write(serverDir.resolve("config").resolve(Links.FILE), l);
		} catch (IOException e) {
			System.err.println("Couldn't write " + Links.FILE + ": " + e);
		}
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
	private Commands commands;
	private java.util.function.LongSupplier nextRestart = () -> 0;

	/** When the schedule restarts the server next (0 for not planned). */
	void useRestart(java.util.function.LongSupplier nextRestart) {
		this.nextRestart = nextRestart;
	}

	/** Where the public command list comes from. */
	private ClaimService claims;

	private MarketService market;

	private PetsService pets;

	void usePets(PetsService pets) {
		this.pets = pets;
	}

	void useMarket(MarketService market) {
		this.market = market;
	}

	void useClaims(ClaimService claims) {
		this.claims = claims;
	}

	void useCommands(Commands commands) {
		this.commands = commands;
	}

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
		writeLinks();
	}

	private Settings readSettings() {
		try {
			if (Files.exists(settingsFile)) {
				Settings s = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
				if (s != null) {
					if (s.hiddenProperties == null) s.hiddenProperties = new ArrayList<>();
					if (s.configs == null) s.configs = new ArrayList<>();
					if (s.infoAddress == null) s.infoAddress = "";
					if (s.links == null) s.links = new ArrayList<>();
					if (s.infoText == null) s.infoText = "";
					if (s.infoLabel == null || s.infoLabel.isBlank()) s.infoLabel = "Server info";
					if (s.website != null && !s.website.isBlank() && s.links.stream().noneMatch(l -> l.names().contains("website"))) {
						Links.Custom w = new Links.Custom();
						w.command = "website";
						w.text = "Our website:";
						w.url = s.website;
						s.links.add(0, w);
					}
					s.website = null;
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
		if (s.commands && commands != null) out.put("commands", commands.publicList());
		long restart = s.restart ? nextRestart.getAsLong() : 0;
		if (restart > System.currentTimeMillis()) out.put("nextRestart", restart);
		if (s.claims && claims != null) {
			Map<String, Object> c = claims.publicInfo();
			if (c != null) out.put("claims", c);
		}
		if (s.borders) {
			List<Map<String, Object>> borders = new ArrayList<>();
			eu.explorerseden.nicecontrolcenter.Borders.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Borders.FILE)).forEach((dim, b) -> {
				if (b.soft <= 0 && b.hard <= 0) return;
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("dim", dim);
				m.put("shape", b.circle() ? "circle" : "square");
				m.put("x", b.x);
				m.put("z", b.z);
				m.put("permanent", b.soft);
				m.put("edge", b.hard);
				borders.add(m);
			});
			if (!borders.isEmpty()) out.put("borders", borders);
		}
		if (s.market && market != null) {
			Map<String, Object> m = market.publicView();
			if (m != null) out.put("market", m);
			List<Map<String, Object>> p = pets == null ? null : pets.publicView();
			if (p != null) {
				out.put("pets", p);
				if (m == null) out.put("petCurrency", eu.explorerseden.nicecontrolcenter.Market.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Market.FILE)).currency);
			}
		}
		if (s.tweaks) {
			var t = eu.explorerseden.nicecontrolcenter.ServerFacts.tweaks(eu.explorerseden.nicecontrolcenter.Tweaks.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Tweaks.FILE)));
			if (!t.isEmpty()) out.put("tweaks", t);
		}
		if (s.performance) out.put("performance", eu.explorerseden.nicecontrolcenter.ServerFacts.performance(Performance.read(serverDir.resolve("config").resolve(Performance.FILE))));
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
			// Off means off for everyone; admins can look at it through the panel's "Open the page" (?preview).
			boolean preview = ctx.queryParam("preview") != null && session != null && session.can(Permissions.SETTINGS_SERVER);
			if (!settings.enabled && !preview) {
				ctx.status(HttpStatus.NOT_FOUND).header("Cache-Control", "no-store").json(Map.of("error", "This page is switched off."));
				return;
			}
			Map<String, Object> out = page();
			if (!settings.enabled) out.put("preview", true);
			ctx.header("Cache-Control", "no-store");
			json(ctx, out);
		});
		routes.get("/api/info", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", settings);
			out.put("host", host);
			out.put("autoAddress", autoAddress());
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
			String address = Links.url(next.infoAddress);
			if (address == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "The info page address must be a web address, like https://info.example.com.");
				return;
			}
			next.infoAddress = address;
			next.website = null;
			next.infoText = next.infoText == null ? "" : next.infoText.strip();
			next.infoLabel = next.infoLabel == null || next.infoLabel.isBlank() ? "Server info" : next.infoLabel.strip();
			if (next.infoText.length() > 200 || next.infoLabel.length() > 80) {
				error(ctx, HttpStatus.BAD_REQUEST, "The /info texts are too long.");
				return;
			}
			if (next.links == null) next.links = new ArrayList<>();
			String linkProblem = Links.validate(next.links);
			if (linkProblem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, linkProblem);
				return;
			}
			Path config = serverDir.resolve("config").normalize();
			for (String rel : next.configs) {
				if (!serverDir.resolve(rel).normalize().startsWith(config)) {
					error(ctx, HttpStatus.BAD_REQUEST, "Only files in config/ can be published.");
					return;
				}
			}
			Files.writeString(settingsFile, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(next));
			settings = next;
			writeLinks();
			audit.log(me.name(), "info.settings", (next.enabled ? "on" : "off") + ", " + next.configs.size() + " config files", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
