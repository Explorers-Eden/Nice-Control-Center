package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Server and JVM settings, kept in /data/panel/settings.json. Moves to Postgres together with the
 * user accounts; until then a plain file keeps the panel usable without a database.
 */
public final class PanelSettings {
	public boolean autoStart = true;
	public boolean autoRestart = true;
	public int stopTimeoutSeconds = 60;
	public String serverJar = "fabric-server-launch.jar";
	public String javaPath = "java";
	public int memoryMinMb = 2048;
	public int memoryMaxMb = 4096;
	public String preset = "aikar";
	public String customArgs = "";

	private static final List<String> PRESETS = List.of("aikar", "zgc", "none");

	/** Returns an error message, or null when the settings are usable. */
	public String validate(List<String> runtimes) {
		if (memoryMaxMb < 512 || memoryMaxMb > 1024 * 1024) return "Maximum memory must be between 512 MB and 1 TB.";
		if (memoryMinMb < 128 || memoryMinMb > memoryMaxMb) return "Minimum memory must be at least 128 MB and not above the maximum.";
		if (!PRESETS.contains(preset)) return "Unknown flag preset.";
		if (stopTimeoutSeconds < 10 || stopTimeoutSeconds > 600) return "Stop timeout must be between 10 and 600 seconds.";
		// A file name only: the jar must sit in the server folder.
		if (serverJar == null || !serverJar.matches("[A-Za-z0-9._+-]+\\.jar")) return "The server jar must be a .jar file name in the server folder.";
		if (!runtimes.contains(javaPath)) return "Choose one of the installed Java runtimes.";
		if (customArgs == null) customArgs = "";
		if (customArgs.contains("\n") || customArgs.length() > 4000) return "Custom flags must be one line.";
		for (String arg : JvmFlags.split(customArgs)) {
			if (!arg.startsWith("-")) return "Custom flags must start with '-' (" + arg + ").";
			if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) return "Set memory with the fields above, not as custom flags.";
		}
		return null;
	}

	public static PanelSettings load(Path file) {
		try {
			if (Files.exists(file)) {
				PanelSettings s = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PanelSettings.class);
				if (s != null) return s;
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read " + file + ", using defaults: " + e.getMessage());
		}
		return new PanelSettings();
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.toJson(this), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
