package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.management.ObjectName;

/**
 * Which classes fill the memory: the JVM's class histogram of live objects (what "jcmd &lt;pid&gt; GC.class_histogram"
 * prints), taken from inside the server, so it also works where the image has no JDK tools. Taking it runs a full
 * garbage collection first, which can pause the server for a moment on a big heap, so it runs off the server thread.
 *
 * <p>The last histogram is kept, so the next one can show which classes grew in between: one histogram shows what is
 * big, two show what leaks.
 */
public final class HeapHistogram {
	public record Row(String className, long instances, long bytes) {
	}

	/** Rows are sorted by bytes, largest first. {@code previous} is the histogram taken before this one, or null. */
	public record Result(List<Row> rows, long totalBytes, long totalInstances, long time, Result previous) {
		public List<Row> top(int count) {
			return rows.subList(0, Math.min(count, rows.size()));
		}

		/** Classes whose name contains {@code filter} (any case), largest first. */
		public List<Row> matching(String filter, int count) {
			String f = filter.toLowerCase(Locale.ROOT);
			return rows.stream().filter(r -> r.className().toLowerCase(Locale.ROOT).contains(f)).limit(count).toList();
		}

		/** Classes that grew most in bytes since {@link #previous}; empty without one. Row values are the change. */
		public List<Row> grown(int count) {
			if (previous == null) return List.of();
			Map<String, Row> before = new HashMap<>();
			for (Row r : previous.rows) before.put(r.className(), r);
			List<Row> changes = new ArrayList<>();
			for (Row r : rows) {
				Row b = before.get(r.className());
				long bytes = r.bytes() - (b == null ? 0 : b.bytes());
				if (bytes > 0) changes.add(new Row(r.className(), r.instances() - (b == null ? 0 : b.instances()), bytes));
			}
			changes.sort(Comparator.comparingLong(Row::bytes).reversed());
			return changes.subList(0, Math.min(count, changes.size()));
		}
	}

	private static Result last;

	private HeapHistogram() {
	}

	public static CompletableFuture<Result> take() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Object text = ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
						"gcClassHistogram", new Object[] { new String[0] }, new String[] { String[].class.getName() });
				synchronized (HeapHistogram.class) {
					Result result = parse(String.valueOf(text), System.currentTimeMillis(), last);
					last = new Result(result.rows(), result.totalBytes(), result.totalInstances(), result.time(), null);
					return result;
				}
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
			}
		});
	}

	/**
	 * A heap dump of the live objects (what "jcmd GC.heap_dump" writes), gzipped, in the server folder's
	 * nicecontrolcenter/heapdumps (download it from the panel's Files). Shows who holds on to objects, which a
	 * histogram can't. Pauses the server while it's written; only the newest two are kept. Off the server thread.
	 */
	public static CompletableFuture<java.nio.file.Path> dump(java.nio.file.Path serverDir) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				java.nio.file.Path dir = serverDir.resolve("nicecontrolcenter").resolve("heapdumps");
				java.nio.file.Files.createDirectories(dir);
				String name = "heap-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
				java.nio.file.Path raw = dir.resolve(name + ".hprof");
				java.nio.file.Files.deleteIfExists(raw);
				ManagementFactory.newPlatformMXBeanProxy(ManagementFactory.getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic",
						com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(raw.toString(), true);
				java.nio.file.Path gz = dir.resolve(name + ".hprof.gz");
				try (var in = java.nio.file.Files.newInputStream(raw);
						var out = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(gz), 1 << 16)) {
					in.transferTo(out);
				}
				java.nio.file.Files.delete(raw);
				try (var files = java.nio.file.Files.list(dir)) {
					List<java.nio.file.Path> old = files.filter(f -> f.getFileName().toString().endsWith(".hprof.gz"))
							.sorted(java.util.Comparator.comparing(java.nio.file.Path::toString).reversed()).skip(2).toList();
					for (java.nio.file.Path f : old) java.nio.file.Files.deleteIfExists(f);
				}
				return gz;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
			}
		});
	}

	/** " 1:  123456  98765432  [B (java.base@25)" lines, then "Total  instances  bytes". */
	static Result parse(String text, long time, Result previous) {
		List<Row> rows = new ArrayList<>();
		long totalBytes = 0;
		long totalInstances = 0;
		for (String line : text.split("\n")) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length >= 3 && parts[0].equals("Total")) {
				totalInstances = Long.parseLong(parts[1]);
				totalBytes = Long.parseLong(parts[2]);
			} else if (parts.length >= 4 && parts[0].endsWith(":")) {
				try {
					rows.add(new Row(readable(parts[3]), Long.parseLong(parts[1]), Long.parseLong(parts[2])));
				} catch (NumberFormatException e) {
					// Not a row.
				}
			}
		}
		return new Result(rows, totalBytes, totalInstances, time, previous);
	}

	/** "[B" → "byte[]", "[Ljava.lang.Object;" → "java.lang.Object[]". */
	private static String readable(String name) {
		return switch (name) {
			case "[B" -> "byte[]";
			case "[C" -> "char[]";
			case "[I" -> "int[]";
			case "[J" -> "long[]";
			case "[S" -> "short[]";
			case "[Z" -> "boolean[]";
			case "[D" -> "double[]";
			case "[F" -> "float[]";
			default -> name.startsWith("[L") && name.endsWith(";") ? name.substring(2, name.length() - 1) + "[]" : name;
		};
	}
}
