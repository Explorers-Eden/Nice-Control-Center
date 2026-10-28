package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * Writes data files on one background thread, so the server thread never waits for the disk. Each write goes to a
 * temporary file first and replaces the real one in one step, like before. A newer write for a file that's still
 * waiting replaces the older one; {@link #flush} waits for everything (server stop).
 */
public final class AsyncFiles {
	private static final Map<Path, String> pending = new ConcurrentHashMap<>();
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Nice Control Center File Writer");
		t.setDaemon(true);
		return t;
	});

	private AsyncFiles() {
	}

	public static void write(Path file, String content) {
		if (pending.put(file, content) != null) return;
		WRITER.execute(() -> {
			String latest = pending.remove(file);
			if (latest == null) return;
			try {
				writeNow(file, latest);
			} catch (IOException e) {
				NiceControlCenter.LOGGER.warn("Files: couldn't save {}: {}", file, e.toString());
			}
		});
	}

	/** Waits (up to 15 seconds) until every queued write is on disk. */
	public static void flush() {
		try {
			WRITER.submit(() -> { }).get(15, TimeUnit.SECONDS);
		} catch (Exception e) {
			NiceControlCenter.LOGGER.warn("Files: waiting for files to be saved: {}", e.toString());
		}
	}

	public static void writeNow(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
