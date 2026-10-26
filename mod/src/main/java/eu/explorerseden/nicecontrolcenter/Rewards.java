package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event rewards: commands (and a message) that run when something happens, like a player's first join, a login,
 * entering a dimension, an in-game time or reaching a level. Kept in config/nicecontrolcenter-rewards.json, written by
 * the panel and read by the mod when the file changes; Minecraft-free so both compile it.
 */
public final class Rewards {
	public static final String FILE = "nicecontrolcenter-rewards.json";

	/** What sets a rule off: id → label, what "value" means ("" = not used), what "number" means. */
	public record Trigger(String id, String label, String value, String number, boolean world) {
	}

	public static final List<Trigger> TRIGGERS = List.of(
			new Trigger("firstJoin", "First join", "", "", false),
			new Trigger("join", "Joins", "", "", false),
			new Trigger("leave", "Leaves", "", "", false),
			new Trigger("dimension", "Enters a dimension", "Dimension (empty = any)", "", false),
			new Trigger("advancement", "Earns an advancement", "Advancement id (empty = any)", "", false),
			new Trigger("death", "Dies", "", "", false),
			new Trigger("kill", "Kills a mob or player", "Entity id (empty = any mob)", "", false),
			new Trigger("level", "Reaches an XP level", "", "Level", false),
			new Trigger("playtime", "Has played for a while", "", "Hours played", false),
			new Trigger("dayTime", "In-game time of day", "", "Time (0 = 6:00, 6000 = noon, 13000 = night, 18000 = midnight)", true),
			new Trigger("gameDay", "In-game day reached", "", "Day", true));

	public static Trigger trigger(String id) {
		for (Trigger t : TRIGGERS) if (t.id().equals(id)) return t;
		return null;
	}

	public static final class Rule {
		public String id = "";
		public String name = "";
		public boolean enabled = true;
		public String trigger = "firstJoin";
		public String value = "";
		public int number = 0;
		/** "everyone" or "player" (then only the player named in player). */
		public String who = "everyone";
		public String player = "";
		/** "always", "oncePerPlayer" or "once" (one time for the whole server). */
		public String repeat = "always";
		/** Per player; 0 = none. */
		public int cooldownMinutes = 0;
		/** Run as the server, at the player (so ~ ~ ~ and @s mean them); without the leading slash. */
		public List<String> commands = new ArrayList<>();
		/** Sent to the player (or everyone) after the commands; § colour codes, same variables. */
		public String message = "";
		public boolean messageEveryone = false;
	}

	public static final class Settings {
		public boolean enabled = true;
		public List<Rule> rules = new ArrayList<>();

		public Settings fill() {
			if (rules == null) rules = new ArrayList<>();
			rules.removeIf(r -> r == null);
			Set<String> ids = new HashSet<>();
			for (Rule r : rules) {
				if (r.id == null || r.id.isBlank() || !ids.add(r.id)) {
					r.id = UUID.randomUUID().toString().substring(0, 8);
					ids.add(r.id);
				}
				if (r.name == null) r.name = "";
				if (r.value == null) r.value = "";
				if (r.who == null) r.who = "everyone";
				if (r.player == null) r.player = "";
				if (r.repeat == null) r.repeat = "always";
				if (r.message == null) r.message = "";
				if (r.commands == null) r.commands = new ArrayList<>();
				List<String> cmds = new ArrayList<>();
				for (String c : r.commands) {
					if (c == null || c.isBlank()) continue;
					String t = c.strip();
					cmds.add(t.startsWith("/") ? t.substring(1) : t);
				}
				r.commands = cmds;
			}
			return this;
		}
	}

	/** Per rule: who it already ran for, and when, for "once" rules and cooldowns. Kept by the mod. */
	public static final class State {
		public Map<String, Map<String, Long>> ran = new LinkedHashMap<>();
	}

	private Rewards() {
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (s.rules.size() > 200) return "At most 200 rules.";
		for (Rule r : s.rules) {
			String n = r.name.isBlank() ? "A rule" : "\"" + r.name + "\"";
			if (trigger(r.trigger) == null) return n + " has an unknown trigger.";
			if (!List.of("always", "oncePerPlayer", "once").contains(r.repeat)) return n + ": unknown repeat setting.";
			if (!r.who.equals("everyone") && !r.who.equals("player")) return n + ": who is everyone or one player.";
			if (r.who.equals("player") && !r.player.matches("[A-Za-z0-9_]{1,16}")) return n + ": name the player it's for.";
			if (r.commands.isEmpty() && r.message.isBlank()) return n + " needs a command or a message.";
			if (r.commands.size() > 30) return n + ": at most 30 commands.";
			for (String c : r.commands) if (c.length() > 1000) return n + ": a command is longer than 1000 characters.";
			if (r.message.length() > 1000) return n + ": the message is longer than 1000 characters.";
			if (r.cooldownMinutes < 0 || r.cooldownMinutes > 525_600) return n + ": the cooldown is 0 to 525,600 minutes.";
			if (r.trigger.equals("dayTime") && (r.number < 0 || r.number > 23_999)) return n + ": the time of day is 0 to 23999.";
			if ((r.trigger.equals("level") || r.trigger.equals("playtime") || r.trigger.equals("gameDay")) && (r.number < 1 || r.number > 1_000_000)) return n + " needs a number of at least 1.";
		}
		return null;
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings().fill();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings().fill();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings().fill();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
