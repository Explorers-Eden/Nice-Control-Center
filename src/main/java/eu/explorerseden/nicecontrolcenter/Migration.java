package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Moves files from the mod's old name (Nice Profiler) to the new one, once. Keeps the dashboard
 * token, so existing links keep working, and the reports folder.
 */
final class Migration {
	private Migration() {
	}

	/** Config files; run before the config is loaded. */
	static void configFiles() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		move(dir.resolve("niceprofiler.json"), dir.resolve("nicecontrolcenter.json"));
		move(dir.resolve("niceprofiler-bossbar.json"), dir.resolve("nicecontrolcenter-bossbar.json"));
	}

	/** Reports and recordings next to the server. */
	static void dataFolder(Path serverDirectory) {
		move(serverDirectory.resolve("niceprofiler"), serverDirectory.resolve("nicecontrolcenter"));
	}

	private static void move(Path from, Path to) {
		if (!Files.exists(from) || Files.exists(to)) {
			return;
		}
		try {
			Files.move(from, to);
			NiceControlCenter.LOGGER.info("Moved {} to {} (the mod was renamed from Nice Profiler)", from.getFileName(), to.getFileName());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not move {} to {}", from, to, e);
		}
	}
}
