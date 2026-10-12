package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maintenance mode, kept in config/nicecontrolcenter-maintenance.json: while on, only operators and the
 * staff list can join, the server list shows its own MOTD (and icon, server-icon-maintenance.png), and
 * everyone else is kicked after a countdown. Written by the panel, read by the mod; Minecraft-free.
 */
public final class Maintenance {
	public static final String FILE = "nicecontrolcenter-maintenance.json";
	public static final String ICON = "server-icon-maintenance.png";

	public static final class Settings {
		public boolean enabled = false;
		/** When it was switched on (ms), so the mod can tell a fresh switch from a restart. */
		public long since = 0;
		public String motd = "§6Down for maintenance§r\n§7We'll be back soon.";
		public String kickMessage = "The server is down for maintenance. We'll be back soon!";
		/** Names that may join besides operators (lower case). */
		public List<String> staff = new ArrayList<>();
		/** Seconds of warnings in chat before players are kicked; 0 = right away. */
		public int countdown = 60;

		public Settings fill() {
			if (motd == null) motd = "";
			if (kickMessage == null || kickMessage.isBlank()) kickMessage = "The server is down for maintenance.";
			if (staff == null) staff = new ArrayList<>();
			staff = staff.stream().filter(n -> n != null && !n.isBlank()).map(n -> n.strip().toLowerCase(Locale.ROOT)).distinct().toList();
			return this;
		}

		public boolean isStaff(String name) {
			return name != null && staff.contains(name.toLowerCase(Locale.ROOT));
		}
	}

	private Maintenance() {
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (s.motd.length() > 300) return "The MOTD can be up to 300 characters.";
		if (s.kickMessage.length() > 500) return "The kick message can be up to 500 characters.";
		if (s.countdown < 0 || s.countdown > 600) return "The countdown is 0 to 600 seconds.";
		if (s.staff.size() > 200) return "At most 200 staff names.";
		for (String n : s.staff) if (!n.matches("[a-z0-9_]{1,16}")) return "\"" + n + "\" isn't a Minecraft name.";
		return null;
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings();
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
