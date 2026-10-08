package eu.explorerseden.nicecontrolcenter.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.mixin.DedicatedServerAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.DedicatedServerSettingsAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.SettingsAccessor;

/**
 * Reads and changes server.properties through the server's own settings object, so the copy in
 * memory and the file stay the same (vanilla saves its in-memory copy, e.g. on /whitelist on, which
 * would undo a change made only in the file). Most values take effect at the next start.
 */
public final class PropertiesEditor {
	/** How a key is shown and checked. */
	public record Key(String key, String group, String type, String description, long min, long max, List<String> options) {
	}

	/** One row for the dashboard. Secrets have {@code value == null}. */
	public record Field(String key, String group, String type, String description, Long min, Long max, List<String> options,
			String value, boolean secret, boolean set, boolean known) {
	}

	private static final Set<String> SECRETS = Set.of("rcon.password", "management-server-secret", "management-server-tls-keystore-password");
	private static final List<String> GROUPS = List.of("Gameplay", "World", "Players & access", "Network", "Resource pack", "Performance",
			"Management & RCON", "Other");
	private static final Map<String, Key> KEYS = new LinkedHashMap<>();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final int KEEP_BACKUPS = 10;

	static {
		bool("hardcore", "Gameplay", "Players are banned on death; difficulty is locked to hard.");
		choice("difficulty", "Gameplay", "Difficulty for new and existing worlds.", "peaceful", "easy", "normal", "hard");
		choice("gamemode", "Gameplay", "Game mode for new players.", "survival", "creative", "adventure", "spectator");
		bool("force-gamemode", "Gameplay", "Put players into the default game mode every time they join.");
		bool("allow-flight", "Gameplay", "Don't kick players for flying (needed for some mods and data packs).");
		number("spawn-protection", "Gameplay", "Radius around spawn that only operators can build in. 0 = off.", 0, 1000);
		number("player-idle-timeout", "Gameplay", "Kick players who are idle this many minutes. 0 = never.", 0, 100000);

		text("level-name", "World", "World folder name.");
		text("level-seed", "World", "Seed for a new world. Only used when the world is created.");
		text("level-type", "World", "World type for a new world, e.g. minecraft:normal or minecraft:flat.");
		text("generator-settings", "World", "JSON settings for custom world types.");
		bool("generate-structures", "World", "Generate structures in new chunks.");
		number("max-world-size", "World", "World border radius in blocks.", 1, 29999984);
		text("initial-enabled-packs", "World", "Data packs enabled when the world is created.");
		text("initial-disabled-packs", "World", "Data packs disabled when the world is created.");
		choice("region-file-compression", "World", "How chunks are compressed on disk.", "deflate", "lz4", "none");

		number("max-players", "Players & access", "Most players online at once.", 0, 100000);
		bool("white-list", "Players & access", "Only players on the whitelist can join.");
		bool("enforce-whitelist", "Players & access", "Kick online players who aren't on the whitelist when it's reloaded.");
		bool("online-mode", "Players & access", "Check accounts with Mojang. Only switch off behind a proxy that checks them.");
		bool("enforce-secure-profile", "Players & access", "Require signed chat from players.");
		bool("prevent-proxy-connections", "Players & access", "Kick players whose IP differs from the one Mojang saw.");
		number("op-permission-level", "Players & access", "Default level for new operators (1–4).", 1, 4);
		number("function-permission-level", "Players & access", "Permission level of functions (1–4).", 1, 4);
		text("motd", "Players & access", "Message shown in the server list.");
		bool("hide-online-players", "Players & access", "Don't show online player names in the server list.");
		bool("enable-code-of-conduct", "Players & access", "Show a code of conduct to players when they join.");
		text("bug-report-link", "Players & access", "Link shown on the client's bug report screen.");
		bool("log-ips", "Players & access", "Write player IP addresses to the log.");
		bool("broadcast-console-to-ops", "Players & access", "Show console command output to online operators.");
		number("chat-spam-threshold-seconds", "Players & access", "Seconds of chat allowed before a spam kick.", 0, 3600);
		number("command-spam-threshold-seconds", "Players & access", "Seconds of commands allowed before a spam kick.", 0, 3600);

		text("server-ip", "Network", "Address to listen on. Empty = all.");
		number("server-port", "Network", "Port players connect to.", 1, 65535);
		bool("enable-status", "Network", "Answer server list pings.");
		bool("enable-query", "Network", "Answer GameSpy4 queries (for server lists and tools).");
		number("query.port", "Network", "Port for queries.", 1, 65535);
		number("network-compression-threshold", "Network", "Packets bigger than this many bytes are compressed. -1 = off.", -1, 1048576);
		number("rate-limit", "Network", "Kick players sending more packets per second than this. 0 = no limit.", 0, 100000);
		bool("accepts-transfers", "Network", "Accept players transferred from another server.");
		bool("use-native-transport", "Network", "Use the faster Linux network code when available.");
		number("status-heartbeat-interval", "Network", "Seconds between status heartbeats. 0 = off.", 0, 100000);

		bool("require-resource-pack", "Resource pack", "Kick players who decline the resource pack.");
		text("resource-pack", "Resource pack", "Direct download link of the resource pack zip.");
		text("resource-pack-sha1", "Resource pack", "SHA-1 of the zip. Without it, clients download the pack on every join.");
		text("resource-pack-id", "Resource pack", "UUID of the pack. Empty = worked out from the link.");
		text("resource-pack-prompt", "Resource pack", "Message in the download prompt, as JSON text, e.g. {\"text\":\"Please accept\"}.");

		number("view-distance", "Performance", "Chunks sent to players in each direction (3–32).", 3, 32);
		number("simulation-distance", "Performance", "Chunks around players where things tick (3–32).", 3, 32);
		number("entity-broadcast-range-percentage", "Performance", "How far away players see entities, in percent of the default.", 10, 1000);
		number("max-tick-time", "Performance", "Milliseconds a single tick may take before the watchdog stops the server. -1 = off.", -1, Integer.MAX_VALUE);
		number("max-chained-neighbor-updates", "Performance", "Limit for chained block updates.", -1, Integer.MAX_VALUE);
		bool("sync-chunk-writes", "Performance", "Wait for every chunk write. false = faster saving on Linux.");
		number("pause-when-empty-seconds", "Performance", "Stop ticking this many seconds after the last player leaves. 0 = never pause.", 0, Integer.MAX_VALUE);
		bool("enable-jmx-monitoring", "Performance", "Expose tick times over JMX.");

		bool("enable-rcon", "Management & RCON", "Allow remote console connections.");
		number("rcon.port", "Management & RCON", "Port for remote console.", 1, 65535);
		text("rcon.password", "Management & RCON", "Remote console password.");
		bool("broadcast-rcon-to-ops", "Management & RCON", "Show remote console output to online operators.");
		bool("management-server-enabled", "Management & RCON", "Enable the management API.");
		text("management-server-host", "Management & RCON", "Address of the management API.");
		number("management-server-port", "Management & RCON", "Port of the management API. 0 = pick one.", 0, 65535);
		text("management-server-secret", "Management & RCON", "Secret for the management API.");
		bool("management-server-tls-enabled", "Management & RCON", "Use TLS for the management API.");
		text("management-server-tls-keystore", "Management & RCON", "Keystore file for TLS.");
		text("management-server-tls-keystore-password", "Management & RCON", "Keystore password.");
		text("management-server-allowed-origins", "Management & RCON", "Allowed browser origins for the management API.");
		text("text-filtering-config", "Management & RCON", "Chat filter service configuration.");
		number("text-filtering-version", "Management & RCON", "Chat filter service version.", 0, 10);
	}

	private PropertiesEditor() {
	}

	private static void bool(String key, String group, String description) {
		KEYS.put(key, new Key(key, group, "bool", description, 0, 0, List.of()));
	}

	private static void number(String key, String group, String description, long min, long max) {
		KEYS.put(key, new Key(key, group, "number", description, min, max, List.of()));
	}

	private static void text(String key, String group, String description) {
		KEYS.put(key, new Key(key, group, "text", description, 0, 0, List.of()));
	}

	private static void choice(String key, String group, String description, String... options) {
		KEYS.put(key, new Key(key, group, "choice", description, 0, 0, List.of(options)));
	}

	public static List<String> groups() {
		return GROUPS;
	}

	private static DedicatedServerSettings settings(MinecraftServer server) {
		return server instanceof DedicatedServer dedicated ? ((DedicatedServerAccessor) dedicated).nicecontrolcenter$settings() : null;
	}

	private static Properties raw(DedicatedServerProperties properties) {
		return ((SettingsAccessor) properties).nicecontrolcenter$properties();
	}

	/** server.properties as the server currently has it, or null in singleplayer. */
	public static Path file(MinecraftServer server) {
		DedicatedServerSettings settings = settings(server);
		return settings == null ? null : ((DedicatedServerSettingsAccessor) settings).nicecontrolcenter$source();
	}

	/** Current value of a key, or "" if unset. Null in singleplayer. */
	public static String get(MinecraftServer server, String key) {
		DedicatedServerSettings settings = settings(server);
		return settings == null ? null : raw(settings.getProperties()).getProperty(key, "");
	}

	/** All keys for the dashboard; secrets without their values. Server thread. */
	public static List<Field> fields(MinecraftServer server) {
		DedicatedServerSettings settings = settings(server);
		if (settings == null) {
			return List.of();
		}
		Properties properties = raw(settings.getProperties());
		Set<String> keys = new TreeSet<>(properties.stringPropertyNames());
		List<Field> result = new ArrayList<>();
		for (Key key : KEYS.values()) {
			if (keys.remove(key.key())) {
				result.add(field(key, properties.getProperty(key.key(), "")));
			}
		}
		for (String unknown : keys) {
			result.add(field(new Key(unknown, "Other", "text", "", 0, 0, List.of()), properties.getProperty(unknown, "")));
		}
		return result;
	}

	private static Field field(Key key, String value) {
		boolean secret = SECRETS.contains(key.key());
		boolean number = key.type().equals("number");
		return new Field(key.key(), key.group(), key.type(), key.description(), number ? key.min() : null, number ? key.max() : null,
				key.options(), secret ? null : value, secret, !value.isEmpty(), KEYS.containsKey(key.key()));
	}

	/** Checks a value; returns a message or null. */
	public static String validate(String key, String value) {
		if (value.contains("\n") || value.contains("\r")) {
			return key + ": must be a single line.";
		}
		Key known = KEYS.get(key);
		if (known == null) {
			return null;
		}
		switch (known.type()) {
			case "bool" -> {
				if (!value.equals("true") && !value.equals("false")) {
					return key + ": must be true or false.";
				}
			}
			case "number" -> {
				try {
					long n = Long.parseLong(value.trim());
					if (n < known.min() || n > known.max()) {
						return key + ": must be between " + known.min() + " and " + known.max() + ".";
					}
				} catch (NumberFormatException e) {
					return key + ": must be a whole number.";
				}
			}
			case "choice" -> {
				if (!known.options().contains(value)) {
					return key + ": must be one of " + String.join(", ", known.options()) + ".";
				}
			}
			default -> {
				if ((key.equals("resource-pack-prompt") || key.equals("generator-settings")) && !value.isBlank()) {
					try {
						JsonParser.parseString(value);
					} catch (RuntimeException e) {
						return key + ": isn't valid JSON.";
					}
				}
				if (key.equals("resource-pack-sha1") && !value.isEmpty() && !value.matches("[0-9a-fA-F]{40}")) {
					return key + ": must be 40 hex characters.";
				}
			}
		}
		return null;
	}

	/**
	 * Changes keys through the server's settings (memory and file) after a backup. Server thread.
	 * Returns an error or null.
	 */
	public static String save(MinecraftServer server, Map<String, String> changes, String who) {
		DedicatedServerSettings settings = settings(server);
		if (settings == null) {
			return "There is no server.properties in singleplayer.";
		}
		if (changes.isEmpty()) {
			return null;
		}
		for (Map.Entry<String, String> change : changes.entrySet()) {
			if (!change.getKey().matches("[a-z0-9.\\-_]+")) {
				return "Unknown key " + change.getKey() + ".";
			}
			String error = validate(change.getKey(), change.getValue());
			if (error != null) {
				return error;
			}
		}
		Path file = ((DedicatedServerSettingsAccessor) settings).nicecontrolcenter$source();
		backup(server, file);
		try {
			settings.update(current -> {
				Properties copy = new Properties();
				copy.putAll(raw(current));
				changes.forEach((key, value) -> copy.setProperty(key, KEYS.containsKey(key) && !KEYS.get(key).type().equals("text") ? value.trim() : value));
				return new DedicatedServerProperties(copy);
			});
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not save server.properties", e);
			return "Could not save: " + e.getMessage();
		}
		List<String> names = changes.keySet().stream().map(k -> SECRETS.contains(k) ? k + " (secret)" : k + "=" + changes.get(k)).toList();
		NiceControlCenter.LOGGER.info("server.properties changed by {}: {}", who, String.join(", ", names));
		return null;
	}

	/**
	 * Changes keys in the file only, keeping every other line as it is. For when the server has
	 * already stopped (updates are installed then).
	 */
	public static void saveFile(Path file, Map<String, String> changes) throws IOException {
		List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
		Map<String, String> left = new LinkedHashMap<>(changes);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int eq = line.indexOf('=');
			if (line.startsWith("#") || eq < 0) {
				continue;
			}
			String key = line.substring(0, eq).trim();
			if (left.containsKey(key)) {
				lines.set(i, key + "=" + escape(left.remove(key)));
			}
		}
		left.forEach((key, value) -> lines.add(key + "=" + escape(value)));
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, lines, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Escapes a value the way java.util.Properties writes it. */
	private static String escape(String value) {
		Properties one = new Properties();
		one.setProperty("k", value);
		try {
			java.io.StringWriter writer = new java.io.StringWriter();
			one.store(writer, null);
			return writer.toString().lines().filter(l -> l.startsWith("k=")).findFirst().map(l -> l.substring(2)).orElse(value);
		} catch (IOException e) {
			return value;
		}
	}

	private static void backup(MinecraftServer server, Path file) {
		if (!Files.exists(file)) {
			return;
		}
		Path dir = server.getServerDirectory().resolve("nicecontrolcenter").resolve("backups");
		try {
			Files.createDirectories(dir);
			Files.copy(file, dir.resolve("server.properties." + LocalDateTime.now().format(STAMP)), StandardCopyOption.REPLACE_EXISTING);
			try (Stream<Path> old = Files.list(dir)) {
				List<Path> backups = old.filter(p -> p.getFileName().toString().startsWith("server.properties."))
						.sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
				for (Path extra : backups.subList(Math.min(KEEP_BACKUPS, backups.size()), backups.size())) {
					Files.deleteIfExists(extra);
				}
			}
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not back up server.properties", e);
		}
	}
}
