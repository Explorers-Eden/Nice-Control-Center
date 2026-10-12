package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * The Commands page: every command on the server (from the mod), grouped by the mod that adds it, with
 * who may use it and a short description. Vanilla's are described here; for the others the admins
 * can write their own, kept in /data/panel/commands.json. The last list is kept on disk so the page
 * also works while the server is off.
 */
final class Commands {
	/** Plain descriptions of the vanilla commands and of common mods' commands, by name. */
	static final Map<String, String> VANILLA = Map.ofEntries(
			Map.entry("advancement", "Give or take advancements."),
			Map.entry("attribute", "Read or change an entity's attributes, like max health or speed."),
			Map.entry("ban", "Ban a player from the server."),
			Map.entry("ban-ip", "Ban an IP address from the server."),
			Map.entry("banlist", "Show the banned players and IP addresses."),
			Map.entry("bossbar", "Create and change boss bars."),
			Map.entry("clear", "Remove items from a player's inventory."),
			Map.entry("clone", "Copy blocks from one area to another."),
			Map.entry("damage", "Hurt an entity."),
			Map.entry("data", "Read or change the data of blocks, entities and storage."),
			Map.entry("datapack", "List, turn on or turn off data packs."),
			Map.entry("debug", "Start or stop a debug profiling session."),
			Map.entry("defaultgamemode", "Set the game mode new players start in."),
			Map.entry("deop", "Take operator rights from a player."),
			Map.entry("dialog", "Show or clear a dialog screen for players."),
			Map.entry("difficulty", "Show or change the difficulty."),
			Map.entry("effect", "Give or clear status effects."),
			Map.entry("enchant", "Enchant the item a player holds."),
			Map.entry("execute", "Run commands as, at, or under conditions of other entities and places."),
			Map.entry("experience", "Give, take or show experience."),
			Map.entry("xp", "Give, take or show experience."),
			Map.entry("fetchprofile", "Look up a player profile."),
			Map.entry("fill", "Fill an area with a block."),
			Map.entry("fillbiome", "Change the biome of an area."),
			Map.entry("forceload", "Keep chunks loaded even with no player near."),
			Map.entry("function", "Run a function from a data pack."),
			Map.entry("gamemode", "Change a player's game mode."),
			Map.entry("gamerule", "Show or change a game rule."),
			Map.entry("give", "Give items to players."),
			Map.entry("help", "List commands or show how one is used."),
			Map.entry("item", "Change items in inventories and slots."),
			Map.entry("jfr", "Start or stop a Java Flight Recorder profile."),
			Map.entry("kick", "Disconnect a player from the server."),
			Map.entry("kill", "Kill entities."),
			Map.entry("list", "Show who's online."),
			Map.entry("locate", "Find the nearest structure, biome or point of interest."),
			Map.entry("loot", "Drop or give loot from a loot table."),
			Map.entry("me", "Say something about yourself in chat (* Name does …)."),
			Map.entry("msg", "Send a private message to a player."),
			Map.entry("tell", "Send a private message to a player."),
			Map.entry("w", "Send a private message to a player."),
			Map.entry("op", "Make a player an operator."),
			Map.entry("pardon", "Unban a player."),
			Map.entry("pardon-ip", "Unban an IP address."),
			Map.entry("particle", "Show particles."),
			Map.entry("perf", "Record server performance for 10 seconds."),
			Map.entry("place", "Place a feature, structure or template."),
			Map.entry("playsound", "Play a sound."),
			Map.entry("publish", "Open a single-player world to the local network."),
			Map.entry("random", "Roll a random number."),
			Map.entry("recipe", "Give or take recipes."),
			Map.entry("reload", "Reload data packs (loot tables, advancements, functions…)."),
			Map.entry("return", "Stop a function and return a value."),
			Map.entry("ride", "Make an entity ride or dismount another."),
			Map.entry("rotate", "Turn an entity."),
			Map.entry("save-all", "Save the world now."),
			Map.entry("save-off", "Stop saving the world automatically."),
			Map.entry("save-on", "Save the world automatically again."),
			Map.entry("say", "Send a message to everyone."),
			Map.entry("schedule", "Run a function later."),
			Map.entry("scoreboard", "Manage scoreboard objectives and scores."),
			Map.entry("seed", "Show the world seed."),
			Map.entry("setblock", "Place a block."),
			Map.entry("setidletimeout", "Kick players after being idle this many minutes."),
			Map.entry("setworldspawn", "Set the world spawn point."),
			Map.entry("spawnpoint", "Set a player's spawn point."),
			Map.entry("spectate", "Watch through another entity's eyes (spectator mode)."),
			Map.entry("spreadplayers", "Teleport entities to random spots in an area."),
			Map.entry("stop", "Save and stop the server."),
			Map.entry("stopsound", "Stop sounds."),
			Map.entry("stopwatch", "Create, read and remove stopwatches."),
			Map.entry("summon", "Spawn an entity."),
			Map.entry("swing", "Make an entity swing its arm."),
			Map.entry("tag", "Add or remove tags on entities."),
			Map.entry("team", "Manage teams."),
			Map.entry("teammsg", "Send a message to your team."),
			Map.entry("tm", "Send a message to your team."),
			Map.entry("teleport", "Teleport entities."),
			Map.entry("tp", "Teleport entities."),
			Map.entry("tellraw", "Send a formatted message to players."),
			Map.entry("test", "Run game tests."),
			Map.entry("tick", "Freeze, slow down, speed up or step the game's ticking."),
			Map.entry("time", "Show or change the time of day."),
			Map.entry("title", "Show a title on players' screens."),
			Map.entry("transfer", "Send players to another server."),
			Map.entry("trigger", "Set a trigger objective (the one command data packs let everyone use)."),
			Map.entry("version", "Show the server's Minecraft version."),
			Map.entry("waypoint", "Manage locator bar waypoints."),
			Map.entry("weather", "Change the weather."),
			Map.entry("whitelist", "Manage the whitelist."),
			Map.entry("worldborder", "Change the world border."),
			// Mods: this one, Explorer's Eden's own and the common server mods.
			Map.entry("nicecontrolcenter", "Nice Control Center: server status, what costs the most, the dashboard link, the lag monitor, recordings and mod updates."),
			Map.entry("ncc", "Nice Control Center: server status, what costs the most, the dashboard link, the lag monitor, recordings and mod updates."),
			Map.entry("nccreply", "Answer a private message the admins sent you from the panel."),
			Map.entry("claim", "Land claims: opens the claim menu (members, what everyone else may do, name and colour, your claim blocks)."),
			Map.entry("merl", "Ask Merl anything about the server or vanilla Minecraft: she finds the right wiki page, guides you to places, quizzes you and chats."),
			Map.entry("nicemerl", "Merl's settings for yourself (her comments and celebrations on or off), guide trails and quiz answers; admins also rebuild her wiki index and place her mannequin."),
			Map.entry("discord", "Discord JustSync: see which Discord account you're linked to or unlink it; admins look up other players, send a message to Discord and reload the bridge."),
			Map.entry("goml", "Get Off My Lawn land claims: trust or untrust players, add owners, list your claims, or escape from someone else's claim."),
			Map.entry("lp", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("luckperms", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("perm", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("perms", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("permission", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("permissions", "LuckPerms: manage permissions, groups and ranks. Without rights you only see the help."),
			Map.entry("chunky", "Chunky: generate the world ahead of time. Set the world, center, radius and shape, then start, pause, continue or cancel."),
			Map.entry("bluemap", "BlueMap web map: rendering status, start or stop rendering, update or purge maps, reload."),
			Map.entry("dh", "Distant Horizons on the server: its settings, generating far-away terrain ahead of time (pregen) and debug info."),
			Map.entry("c2me", "C2ME (faster chunk loading and generation): a debug tool for chunks that aren't ticking."),
			Map.entry("fallingtree", "FallingTree: switch chopping down whole trees at once on or off, for yourself."),
			Map.entry("polymer", "Polymer (lets mods add content without a client mod): the resource pack, statistics and debug tools."),
			Map.entry("servercore", "ServerCore: performance status, its dynamic settings (mob caps, chunk tick, simulation and view distance) and reloading its config."),
			Map.entry("sc", "ServerCore: performance status, its dynamic settings (mob caps, chunk tick, simulation and view distance) and reloading its config."),
			Map.entry("mobcaps", "ServerCore: how full the mob caps are around you."),
			Map.entry("statistics", "ServerCore: count entities and block entities by type or by player, to find what causes lag."),
			Map.entry("spark", "spark profiler: find what makes the server lag (profiler, health, heap and GC reports)."),
			Map.entry("tps", "Show the server's ticks per second."));

	/** Parts of a command that need other rights ("nicemerl reindex"), described on their own. */
	static final Map<String, String> SUBS = Map.ofEntries(
			Map.entry("nicecontrolcenter monitor", "Switch the lag monitor on or off: chat warnings when the server starts lagging, with the likely cause."),
			Map.entry("nicecontrolcenter web", "Get a link to the web dashboard; regen makes a new link, port shows or changes its port."),
			Map.entry("nicecontrolcenter updates", "Check the mods for updates, set the Discord webhook for update news, skip or roll back an update."),
			Map.entry("nicecontrolcenter record", "Start, stop or check a performance recording, a report of what happened during lag."),
			Map.entry("nicemerl reindex", "Rebuild Merl's wiki search index (after the wiki changed)."),
			Map.entry("nicemerl guide", "A sparkle trail to a place, as the [Guide me] button after coordinates; stop ends it."),
			Map.entry("nicemerl mannequin", "Place a mannequin of Merl where you stand, or remove it."),
			Map.entry("nicemerl comments", "Turn Merl's chatter about what you're up to on or off, for yourself."),
			Map.entry("nicemerl celebrate", "Turn Merl cheering when you get big advancements on or off, for yourself."),
			Map.entry("goml admin", "Claim admin tools: admin mode, remove claims or owners, claim info, list everyone's claims, reload."),
			Map.entry("claim mode", "Enter or leave claim mode: you get the claim tool to claim land by right-clicking two corners, and it's taken away again when you leave."),
			Map.entry("claim list", "Your claims and how many claim blocks you have left."),
			Map.entry("claim trust", "Make a player a member of the claim you stand in (visitor, containers, builder or co-owner)."),
			Map.entry("claim untrust", "Remove a player from the claim you stand in."),
			Map.entry("claim abandon", "Give up the claim you stand in; you get its claim blocks back."),
			Map.entry("claim admin", "Claim admin tools: bypass claims, delete the claim you stand in, show or change a player's claim blocks."),
			Map.entry("servercore reload", "Reload ServerCore's config file."),
			Map.entry("servercore settings", "Change ServerCore's dynamic limits (mob caps, chunk tick, simulation and view distance) right away."),
			Map.entry("polymer generate-pack", "Build the resource pack that Polymer sends to players."),
			Map.entry("polymer blocks_module_state_report", "Debug: a report of the block states Polymer uses."),
			Map.entry("polymer client-item", "Debug: show the item the client sees for the one you hold."),
			Map.entry("polymer export-registry", "Debug: export the registries to a file."),
			Map.entry("polymer target-block", "Debug: show the block you look at as the client sees it."),
			Map.entry("polymer target-item", "Debug: show the item you hold as the client sees it."));

	private final Path cacheFile;
	private final Path notesFile;
	private final Supervisor server;
	private final DashboardProxy mod;
	private final Audit audit;
	private volatile JsonObject last;

	Commands(Path dataDir, Supervisor server, DashboardProxy mod, Audit audit) {
		this.cacheFile = dataDir.resolve("commands-cache.json");
		this.notesFile = dataDir.resolve("commands.json");
		this.server = server;
		this.mod = mod;
		this.audit = audit;
	}

	private Map<String, String> notes() {
		try {
			if (Files.exists(notesFile)) {
				Map<String, String> m = Json.GSON.fromJson(Files.readString(notesFile), new TypeToken<TreeMap<String, String>>() { }.getType());
				if (m != null) return m;
			}
		} catch (IOException | RuntimeException e) {
			// None yet.
		}
		return new TreeMap<>();
	}

	private JsonObject list() {
		// Fresh enough for a minute: the public info page reads this on every visit.
		if (last != null && last.has("at") && System.currentTimeMillis() - last.get("at").getAsLong() < 60_000 && server.running()) return last;
		if (server.running()) {
			JsonObject fresh = mod.get("/api/commands");
			if (fresh != null && fresh.has("commands")) {
				fresh.addProperty("at", System.currentTimeMillis());
				last = fresh;
				try {
					Files.writeString(cacheFile, fresh.toString(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					// Kept in memory.
				}
				return fresh;
			}
		}
		if (last == null) {
			try {
				if (Files.exists(cacheFile)) last = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				// Nothing yet.
			}
		}
		return last;
	}

	/** The command list with descriptions filled in (see {@link #describe}). */
	private JsonArray described(JsonObject src) {
		Map<String, String> notes = notes();
		JsonArray list = src.getAsJsonArray("commands").deepCopy();
		for (JsonElement e : list) {
			JsonObject c = e.getAsJsonObject();
			String name = c.get("name").getAsString();
			String alias = c.has("aliasOf") ? c.get("aliasOf").getAsString() : null;
			describe(c, name, alias, notes);
			if (c.has("subs")) {
				for (JsonElement se : c.getAsJsonArray("subs")) {
					JsonObject sub = se.getAsJsonObject();
					describe(sub, sub.get("name").getAsString(), null, notes);
				}
			}
		}
		return list;
	}

	/** Players can use it without operator rights: level 0, or a player-only check that names no permission node. */
	private static boolean forEveryone(JsonObject c) {
		int level = c.get("level").getAsInt();
		return level == 0 || (level == -1 && !c.has("permission"));
	}

	/**
	 * For the public info page: the commands every player may use, by mod, with their other names
	 * (/tp for /teleport) and without the parts that need more rights (their usage lines too).
	 */
	List<Map<String, Object>> publicList() {
		JsonObject src = list();
		if (src == null) return List.of();
		JsonArray all = described(src);
		Map<String, List<String>> aliases = new HashMap<>();
		for (JsonElement e : all) {
			JsonObject c = e.getAsJsonObject();
			if (c.has("aliasOf") && forEveryone(c)) aliases.computeIfAbsent(c.get("aliasOf").getAsString(), k -> new ArrayList<>()).add(c.get("name").getAsString());
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (JsonElement e : all) {
			JsonObject c = e.getAsJsonObject();
			if (c.has("aliasOf") || !forEveryone(c)) continue;
			String name = c.get("name").getAsString();
			List<String> hidden = new ArrayList<>();
			List<Map<String, Object>> parts = new ArrayList<>();
			if (c.has("subs")) {
				for (JsonElement se : c.getAsJsonArray("subs")) {
					JsonObject sub = se.getAsJsonObject();
					String subName = sub.get("name").getAsString();
					if (!forEveryone(sub)) {
						hidden.add("/" + subName);
						continue;
					}
					Map<String, Object> part = new LinkedHashMap<>();
					part.put("name", subName);
					if (sub.has("description")) part.put("description", sub.get("description").getAsString());
					parts.add(part);
				}
			}
			List<String> usage = new ArrayList<>();
			for (JsonElement u : c.getAsJsonArray("usage")) {
				String line = u.getAsString();
				// "/goml [admin] …" or "/goml admin …": a part that needs more rights.
				String plain = line.replace("[", "").replace("]", "").replace("(", "").replace(")", "");
				if (hidden.stream().noneMatch(h -> plain.equals(h) || plain.startsWith(h + " "))) usage.add(line);
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("mod", c.get("mod").getAsString());
			m.put("modName", c.get("modName").getAsString());
			if (c.has("description")) m.put("description", c.get("description").getAsString());
			m.put("usage", usage);
			if (aliases.containsKey(name)) m.put("aliases", aliases.get(name));
			if (!parts.isEmpty()) m.put("parts", parts);
			// Separate commands that are the same thing (/lp, /luckperms, /perms…): one entry, the others as names.
			Map<String, Object> same = c.has("description") ? out.stream()
					.filter(o -> o.get("mod").equals(m.get("mod")) && m.get("description").equals(o.get("description"))).findFirst().orElse(null) : null;
			if (same != null) {
				@SuppressWarnings("unchecked")
				List<String> names = (List<String>) same.computeIfAbsent("aliases", k -> new ArrayList<String>());
				names.add(name);
				if (aliases.containsKey(name)) names.addAll(aliases.get(name));
				continue;
			}
			if (m.containsKey("aliases")) m.put("aliases", new ArrayList<>(aliases.get(name)));
			out.add(m);
		}
		return out;
	}

	private static void describe(JsonObject c, String name, String alias, Map<String, String> notes) {
		String mine = notes.get(name);
		if (mine != null && !mine.isBlank()) {
			c.addProperty("description", mine);
			c.addProperty("custom", true);
			return;
		}
		String text = VANILLA.containsKey(name) ? VANILLA.get(name) : SUBS.get(name);
		if (text == null && alias != null) text = notes.get(alias) != null && !notes.get(alias).isBlank() ? notes.get(alias) : VANILLA.get(alias);
		if (text != null) c.addProperty("description", text);
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Descriptions: your own text first, then the built-in one, then (for an alias) the command it stands for.
		routes.get("/api/commands", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			JsonObject src = list();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("running", server.running());
			out.put("canEdit", me.can(Permissions.SETTINGS_SERVER));
			if (src == null) {
				out.put("commands", List.of());
				json(ctx, out);
				return;
			}
			out.put("commands", described(src));
			if (src.has("at")) out.put("updated", src.get("at").getAsLong());
			json(ctx, out);
		}));
		routes.post("/api/commands/describe", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			String name = str(body, "name");
			String text = str(body, "description").replaceAll("[\\r\\n]+", " ").strip();
			if (name.isBlank() || name.length() > 64 || text.length() > 300) {
				error(ctx, HttpStatus.BAD_REQUEST, "Descriptions can be up to 300 characters.");
				return;
			}
			Map<String, String> notes = notes();
			if (text.isEmpty()) notes.remove(name);
			else notes.put(name, text);
			Files.writeString(notesFile, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(notes), StandardCharsets.UTF_8);
			audit.log(me.name(), "commands.describe", "/" + name + (text.isEmpty() ? ": description removed" : ": " + text), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
