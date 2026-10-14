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

	/** A link command of the server's own, like /discord. */
	public static final class Custom {
		/** The command without the slash. */
		public String command = "";
		/** More names for the same command. */
		public List<String> aliases = new ArrayList<>();
		/** The text before the link, like "Join our Discord:". */
		public String text = "";
		/** What the link says (the address without https:// when empty). */
		public String label = "";
		public String url = "";

		/** The command and its aliases. */
		public List<String> names() {
			List<String> out = new ArrayList<>();
			out.add(command);
			out.addAll(aliases);
			return out;
		}
	}

	/** Names the mod uses itself, never taken by an own link. */
	public static final Set<String> RESERVED = Set.of("info", "pets", "market", "claim", "ncc", "nicecontrolcenter", "merl", "nccreply");

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
			if (c.command.isEmpty()) return "Every link needs a command.";
			for (String n : c.names()) {
				if (!n.matches("[a-z0-9_-]{1,32}")) return "\"" + n + "\" can't be a command: use letters, digits, - and _ (up to 32).";
				if (RESERVED.contains(n)) return "/" + n + " is already a command of the mod.";
				if (!seen.add(n)) return "/" + n + " is used twice.";
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
