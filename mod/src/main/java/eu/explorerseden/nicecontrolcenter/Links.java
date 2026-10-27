package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Links players get in the game: /info (the panel's public info page) and any number of own link commands
 * (like /website or /discord, with aliases). Written by the panel to config/nicecontrolcenter-links.json, read
 * by the mod (own commands are registered again when the file changes). Minecraft-free.
 */
public final class Links {
	public static final String FILE = "nicecontrolcenter-links.json";

	private Links() {
	}

	public static final class Settings {
		/** The info page's address, or empty when there's none. */
		public String info = "";
		/** False while the info page is switched off. */
		public boolean infoOn = false;
		/** What /info says before the link, and the link's text. */
		public String infoText = "Everything about how this server works:";
		public String infoLabel = "Server info";
		/** Own link commands. */
		public List<Custom> custom = new ArrayList<>();
	}

	/** A command of the server's own: a link like /discord, a text like /rules, or an alias like /spawn. */
	public static final class Custom {
		/** "link" (a clickable address), "text" (lines of text) or "alias" (runs other commands). */
		public String type = "link";
		/** The command without the slash. */
		public String command = "";
		/** More names for the same command. */
		public List<String> aliases = new ArrayList<>();
		/** The text before the link, like "Join our Discord:". */
		public String text = "";
		/** What the link says (the address without https:// when empty). */
		public String label = "";
		public String url = "";
		/** Alias: the commands it runs, without the slash; {player} is who typed it, {args} what they typed after it. */
		public List<String> run = new ArrayList<>();
		/** Alias: run with the server's rights instead of the player's own. */
		public boolean asServer = false;
		/** Text: the lines it shows (§ colour codes). */
		public String body = "";
		/** Who may use it: 0 everyone, 1-4 the operator level; a permissions mod can decide with the node instead. */
		public int level = 0;
		/** The permission node; empty = nicecontrolcenter.command.&lt;command&gt;. */
		public String permission = "";
		/** What the panel's command list and the help say it does. */
		public String description = "";

		public String node() {
			return permission == null || permission.isBlank() ? "nicecontrolcenter.command." + command : permission;
		}

		/** The command and its aliases. */
		public List<String> names() {
			List<String> out = new ArrayList<>();
			out.add(command);
			out.addAll(aliases);
			return out;
		}
	}

	/** Names the mod uses itself, never taken by an own link. */
	public static final Set<String> RESERVED = Set.of("info", "pets", "market", "claim", "ncc", "nicecontrolcenter", "merl", "nccreply", "fly", "reward", "perks", "joinmessage", "inbox", "game", "dungeon", "party");

	/** Checks and tidies the own links (lower-case names, full addresses). Error for the panel, or null. */
	public static String validate(List<Custom> custom) {
		if (custom == null) return null;
		if (custom.size() > 50) return "At most 50 links.";
		Set<String> seen = new HashSet<>();
		for (Custom c : custom) {
			if (c.aliases == null) c.aliases = new ArrayList<>();
			c.command = c.command == null ? "" : c.command.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
			c.aliases = c.aliases.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT)).distinct().toList();
			c.text = c.text == null ? "" : c.text.strip();
			c.label = c.label == null ? "" : c.label.strip();
			if (c.type == null || !List.of("link", "text", "alias").contains(c.type)) c.type = "link";
			if (c.run == null) c.run = new ArrayList<>();
			c.run = c.run.stream().filter(r -> r != null && !r.isBlank()).map(r -> r.strip().replaceFirst("^/", "")).toList();
			c.body = c.body == null ? "" : c.body.replace("\r\n", "\n");
			c.permission = c.permission == null ? "" : c.permission.strip();
			c.description = c.description == null ? "" : c.description.strip();
			if (c.level < 0 || c.level > 4) return "/" + c.command + ": the operator level is 0 to 4.";
			if (!c.permission.isEmpty() && !c.permission.matches("[a-z0-9_.-]{1,100}")) return "/" + c.command + ": a permission node is letters, digits, dots, - and _.";
			if (c.command.isEmpty()) return "Every command needs a name.";
			for (String n : c.names()) {
				if (!n.matches("[a-z0-9_-]{1,32}")) return "\"" + n + "\" can't be a command: use letters, digits, - and _ (up to 32).";
				if (RESERVED.contains(n)) return "/" + n + " is already a command of the mod.";
				if (!seen.add(n)) return "/" + n + " is used twice.";
			}
			if (c.type.equals("alias")) {
				if (c.run.isEmpty()) return "/" + c.command + " needs at least one command to run.";
				if (c.run.size() > 20) return "/" + c.command + ": at most 20 commands.";
				for (String r : c.run) {
					if (r.length() > 500) return "/" + c.command + ": a command is longer than 500 characters.";
					String first = r.split(" ", 2)[0].toLowerCase(Locale.ROOT);
					if (c.names().contains(first)) return "/" + c.command + " can't run itself.";
				}
				continue;
			}
			if (c.type.equals("text")) {
				if (c.body.isBlank()) return "/" + c.command + " needs some text.";
				if (c.body.length() > 4000) return "The text of /" + c.command + " is longer than 4000 characters.";
				continue;
			}
			String u = url(c.url);
			if (u == null || u.isEmpty()) return "The link for /" + c.command + " needs a web address, like https://example.com.";
			c.url = u;
			if (c.text.length() > 200 || c.label.length() > 80) return "The text of /" + c.command + " is too long.";
		}
		return null;
	}

	/** An http(s) address as typed (or with https:// added), or null when it isn't one. */
	public static String url(String in) {
		if (in == null) return null;
		String t = in.strip();
		if (t.isEmpty()) return "";
		if (!t.matches("(?i)https?://.*")) t = "https://" + t;
		return t.matches("(?i)https?://[^\\s/?#]+\\.[^\\s/?#]+[^\\s]*") || t.matches("(?i)https?://localhost(:\\d+)?[^\\s]*") ? t : null;
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null) return new Settings();
			if (s.info == null) s.info = "";
			if (s.infoText == null) s.infoText = "";
			if (s.infoLabel == null || s.infoLabel.isBlank()) s.infoLabel = "Server info";
			if (s.custom == null || validate(s.custom) != null) s.custom = new ArrayList<>();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
