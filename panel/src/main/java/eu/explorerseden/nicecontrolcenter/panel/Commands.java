package eu.explorerseden.nicecontrolcenter.panel;

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
import java.util.LinkedHashMap;
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
	/** Plain descriptions of the vanilla commands. */
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
			// This mod's own.
			Map.entry("nicecontrolcenter", "Nice Control Center: server status, the dashboard link, the lag monitor, recordings and mod updates."),
			Map.entry("nccreply", "Answer a private message the admins sent you from the panel."));

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

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/commands", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			JsonObject src = list();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("running", server.running());
			out.put("canEdit", me.can(Permissions.SETTINGS_SERVER));
			if (src == null) {
				out.put("commands", java.util.List.of());
				json(ctx, out);
				return;
			}
			Map<String, String> notes = notes();
			JsonObject copy = src.deepCopy();
			for (JsonElement e : copy.getAsJsonArray("commands")) {
				JsonObject c = e.getAsJsonObject();
				String name = c.get("name").getAsString();
				String from = c.get("mod").getAsString();
				boolean vanilla = "minecraft".equals(from) || "nicecontrolcenter".equals(from);
				// An alias without its own text reads like the command it points to.
				String alias = c.has("aliasOf") ? c.get("aliasOf").getAsString() : null;
				String mine = notes.get(name);
				if (mine != null && !mine.isBlank()) {
					c.addProperty("description", mine);
					c.addProperty("custom", true);
				} else if (vanilla && VANILLA.containsKey(name)) {
					c.addProperty("description", VANILLA.get(name));
				} else if (alias != null && notes.get(alias) != null && !notes.get(alias).isBlank()) {
					c.addProperty("description", notes.get(alias));
				} else if (alias != null && vanilla && VANILLA.containsKey(alias)) {
					c.addProperty("description", VANILLA.get(alias));
				}
			}
			out.put("commands", copy.get("commands"));
			if (copy.has("at")) out.put("updated", copy.get("at").getAsLong());
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
