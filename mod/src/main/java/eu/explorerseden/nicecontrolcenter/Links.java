package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Links players get in the game: /info (the panel's public info page) and /website. Written by the panel
 * to config/nicecontrolcenter-links.json, read by the mod when a player asks. Minecraft-free.
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
		/** The server's website, or empty. */
		public String website = "";
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
			if (s.website == null) s.website = "";
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
