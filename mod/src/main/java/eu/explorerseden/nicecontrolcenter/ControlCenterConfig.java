package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/** config/nicecontrolcenter.json */
public final class ControlCenterConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Run the live monitor when the server starts. Switch at runtime with /ncc monitor on|off. */
	public boolean monitor_enabled = true;
	/** Serve the web dashboard. */
	public boolean web_enabled = true;
	/** "auto" = 127.0.0.1 in singleplayer, 0.0.0.0 on dedicated servers. */
	public String bind = "auto";
	public int port = 8765;
	/** Base URL to print in links, e.g. "http://play.example.com:8765". Empty = work it out. */
	public String public_url = "";
	/** Secret part of the dashboard link. Made by the mod (see token_hours); /ncc web regen makes a new one now. */
	public String token = "";
	/**
	 * Hours a dashboard link works before the mod replaces its token (old links stop working), also
	 * across restarts. 0 = a new token at every server start.
	 */
	public int token_hours = 0;
	/** When the current token was made (milliseconds since 1970). Kept by the mod. */
	public long token_created = 0;
	/** How often the sampler looks at the server thread. Lower = more detail, more overhead. */
	public int sampler_interval_ms = 20;
	/** Ticks slower than this are recorded as lag spikes. */
	public int spike_threshold_ms = 100;
	/** Recordings stop on their own after this long. */
	public int max_recording_hours = 24;

	/** Show the live server log in the dashboard. */
	public boolean web_console = true;
	/** Allow running commands from the dashboard console (full rights, like the server console). */
	public boolean web_console_commands = true;
	/** Let the dashboard change data pack settings (the same ones as in the packs' settings dialogs). */
	public boolean web_settings_edit = true;

	/** Regular expressions for log messages the error watcher should ignore completely. */
	public java.util.List<String> error_ignore = new java.util.ArrayList<>();

	/**
	 * Mod and data pack updates: "auto" = download and install at the next restart, "stage" = download,
	 * install after approval in the dashboard, "check" = only show what's new, "off" = never check.
	 */
	public String update_mode = "auto";
	/** Hours between update checks (plus one check shortly after start). */
	public int update_check_hours = 24;
	/** Modrinth version types to accept: "release", "beta", "alpha". */
	public java.util.List<String> update_channels = new java.util.ArrayList<>(java.util.List.of("release"));
	/** Mods or data packs to update from GitHub releases: {"<mod id or pack file name without .zip>": "owner/repo"}. */
	public java.util.Map<String, String> update_github = new java.util.LinkedHashMap<>();
	/** Mod ids or pack file names (without .zip) that are never updated. */
	public java.util.List<String> update_ignore = new java.util.ArrayList<>();
	/** How many backup batches of replaced versions to keep. */
	public int update_keep_backups = 10;

	/**
	 * Where the server resource pack comes from: a direct "https://…/pack.zip" link,
	 * "github:owner/repo" (optionally "@tag" and "#part-of-file-name") or "modrinth:project".
	 * Empty = keep the link in server.properties and only keep its SHA-1 right.
	 */
	public String resource_pack_source = "";
	/** Minutes between resource pack checks. 0 = never. */
	public int resource_pack_check_minutes = 10;
	/** Lets a build script start a resource pack check (POST /api/updates/resourcepack/check). Empty = off. */
	public String resource_pack_webhook_token = "";
	/** Let the dashboard change server.properties. */
	public boolean web_properties_edit = true;
	/** Let the dashboard change gamerules. */
	public boolean web_gamerules_edit = true;
	/** Let the dashboard create and change scheduled commands (they run with full rights, like the console). */
	public boolean web_schedule = true;
	/** Shown before private messages from the dashboard. */
	public String message_prefix = "[Admin]";
	/** Color of that prefix: a Minecraft color name (gold, red, aqua, …) or "#RRGGBB". */
	public String message_color = "gold";
	/** Let the dashboard kick and message players. */
	public boolean web_player_actions = true;
	/** Command storage with the player database (homes, graves, waypoints) shown in the Players tab. Empty = none. */
	public String players_storage = "eden:database";

	/** Tell operators (and nicecontrolcenter.notify) in chat when the server lags. */
	public boolean alerts_enabled = true;
	/** Lag = average MSPT above this ... */
	public double alert_mspt = 45;
	/** ... or TPS below this ... */
	public double alert_tps = 18;
	/** ... for this many seconds in a row. Recovery needs the same time under the limits. */
	public int alert_seconds = 15;
	/** Minimum time between two lag alerts. */
	public int alert_cooldown_minutes = 10;
	/** Save a report of every lag episode, including the 5 minutes before it. */
	public boolean auto_record_lag = true;
	/** Automatic lag reports stop after this long even if the lag continues. */
	public int auto_record_max_minutes = 30;
	/** At most this many automatic lag reports per day. */
	public int auto_record_per_day = 10;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("nicecontrolcenter.json");
	}

	/** When the file was last written by us, so the watcher doesn't reload our own saves. */
	private static volatile long savedAt;

	public static ControlCenterConfig load() {
		ControlCenterConfig config = read();
		if (config == null) {
			config = new ControlCenterConfig();
		}
		config.save();
		return config;
	}

	/** Reads the file without saving it; null if it's missing or broken. Missing values get their defaults. */
	public static ControlCenterConfig read() {
		Path path = path();
		ControlCenterConfig config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, ControlCenterConfig.class);
			} catch (IOException | JsonParseException e) {
				NiceControlCenter.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		if (config == null) {
			return null;
		}
		if (config.update_channels == null) {
			config.update_channels = new java.util.ArrayList<>(java.util.List.of("release"));
		}
		if (config.update_github == null) {
			config.update_github = new java.util.LinkedHashMap<>();
		}
		if (config.update_ignore == null) {
			config.update_ignore = new java.util.ArrayList<>();
		}
		if (config.error_ignore == null) {
			config.error_ignore = new java.util.ArrayList<>();
		}
		if (config.update_mode == null) {
			config.update_mode = "auto";
		}
		if (config.message_prefix == null) {
			config.message_prefix = "[Admin]";
		}
		if (config.message_color == null || config.message_color.isBlank()) {
			config.message_color = "gold";
		}
		if (config.players_storage == null) {
			config.players_storage = "";
		}
		if (config.resource_pack_source == null) {
			config.resource_pack_source = "";
		}
		if (config.resource_pack_webhook_token == null) {
			config.resource_pack_webhook_token = "";
		}
		if (config.token == null || config.token.isBlank()) {
			config.renewToken();
		}
		return config;
	}

	/** True if someone else changed the file since we last saved it. */
	public static boolean changedOnDisk() {
		try {
			return Files.exists(path()) && Files.getLastModifiedTime(path()).toMillis() != savedAt;
		} catch (IOException e) {
			return false;
		}
	}

	/** Copies every setting from {@code other} into this object (the one the rest of the mod holds). */
	public void copyFrom(ControlCenterConfig other) {
		for (java.lang.reflect.Field field : ControlCenterConfig.class.getFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				field.set(this, field.get(other));
			} catch (IllegalAccessException e) {
				// Public fields; can't happen.
			}
		}
	}

	private static final java.util.Map<String, String> COLOR_ALIASES = java.util.Map.ofEntries(
			java.util.Map.entry("purple", "dark_purple"), java.util.Map.entry("violet", "dark_purple"),
			java.util.Map.entry("pink", "light_purple"), java.util.Map.entry("magenta", "light_purple"),
			java.util.Map.entry("orange", "gold"), java.util.Map.entry("grey", "gray"), java.util.Map.entry("dark_grey", "dark_gray"),
			java.util.Map.entry("cyan", "aqua"), java.util.Map.entry("turquoise", "aqua"), java.util.Map.entry("lime", "green"),
			java.util.Map.entry("light_green", "green"), java.util.Map.entry("light_blue", "aqua"), java.util.Map.entry("navy", "dark_blue"));
	private static final java.util.Set<String> COLORS = java.util.Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
			"dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

	/**
	 * A text color Minecraft accepts: one of its 16 names, a common alias ("purple" → dark_purple) or a
	 * hex color with or without "#". Null if it isn't a color.
	 */
	public static String color(String text) {
		if (text == null) {
			return null;
		}
		String clean = text.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
		if (COLORS.contains(clean)) {
			return clean;
		}
		if (COLOR_ALIASES.containsKey(clean)) {
			return COLOR_ALIASES.get(clean);
		}
		String hex = clean.startsWith("#") ? clean.substring(1) : clean;
		return hex.matches("[0-9a-f]{6}") ? "#" + hex : null;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(this, writer);
			}
			savedAt = Files.getLastModifiedTime(path()).toMillis();
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save {}", path(), e);
		}
	}

	/** A new dashboard token; old links stop working. */
	public void renewToken() {
		token = newToken();
		token_created = System.currentTimeMillis();
	}

	/** True when token_hours is set and the token is older than that. */
	public boolean tokenExpired() {
		return token_hours > 0 && System.currentTimeMillis() - token_created >= token_hours * 3_600_000L;
	}

	public static String newToken() {
		byte[] bytes = new byte[18];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}
