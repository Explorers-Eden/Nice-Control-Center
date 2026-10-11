package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Size on disk for the stat tiles: the world, the whole server folder and the free space left on its
 * drive. Measured on a background thread every two minutes (walking a big world takes a moment).
 */
public final class DiskUsage {
	private static ScheduledExecutorService worker;
	private static volatile Map<String, Object> latest;

	private DiskUsage() {
	}

	public static synchronized void start(Path world, Path serverDir) {
		stop();
		worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Nice Control Center disk usage");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		Path w = world.toAbsolutePath().normalize();
		Path s = serverDir.toAbsolutePath().normalize();
		worker.scheduleWithFixedDelay(() -> measure(w, s), 5, 120, TimeUnit.SECONDS);
	}

	public static synchronized void stop() {
		if (worker != null) worker.shutdownNow();
		worker = null;
	}

	/** world, server, free and total bytes, measured at; null until the first measurement. */
	public static Map<String, Object> latest() {
		return latest;
	}

	private static void measure(Path world, Path serverDir) {
		try {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("world", size(world));
			out.put("server", size(serverDir));
			FileStore store = Files.getFileStore(serverDir);
			out.put("free", store.getUsableSpace());
			out.put("total", store.getTotalSpace());
			out.put("at", System.currentTimeMillis());
			latest = out;
		} catch (IOException | RuntimeException e) {
			// Keep the last measurement.
		}
	}

	private static long size(Path dir) throws IOException {
		long[] total = { 0 };
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				total[0] += attrs.size();
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				// Files vanish while the server saves (.tmp, region rewrites).
				return FileVisitResult.CONTINUE;
			}
		});
		return total[0];
	}
}
