package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-modified times of settings files, kept fresh by a background thread every 2 seconds, so code on the server
 * thread that checks "did the panel change this file?" every few seconds never waits for the disk itself.
 */
public final class FileWatch {
	private static final Map<Path, Long> times = new ConcurrentHashMap<>();
	private static volatile Thread thread;

	private FileWatch() {
	}

	/**
	 * The file's last-modified time in ms (0 when it doesn't exist), as of at most 2 seconds ago. The first call for a
	 * file reads it right away. Declared like Files.getLastModifiedTime so callers keep their handling; it never throws.
	 */
	public static long modified(Path file) throws IOException {
		Long t = times.get(file);
		if (t == null) {
			t = read(file);
			times.put(file, t);
			start();
		}
		return t;
	}

	private static long read(Path file) {
		try {
			return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	private static synchronized void start() {
		if (thread != null) return;
		Thread t = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}
				for (Path f : times.keySet()) times.put(f, read(f));
			}
		}, "Nice Control Center File Watch");
		t.setDaemon(true);
		t.start();
		thread = t;
	}
}
