package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.management.ObjectName;

/**
 * Which classes fill the memory: the JVM's class histogram of live objects (what "jcmd &lt;pid&gt; GC.class_histogram"
 * prints), taken from inside the server, so it also works where the image has no JDK tools. Taking it runs a full
 * garbage collection first, which can pause the server for a moment on a big heap, so it runs off the server thread.
 */
public final class HeapHistogram {
	public record Row(String className, long instances, long bytes) {
	}

	public record Result(List<Row> top, long totalBytes, long totalInstances) {
	}

	private HeapHistogram() {
	}

	public static CompletableFuture<Result> take(int top) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Object text = ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
						"gcClassHistogram", new Object[] { new String[0] }, new String[] { String[].class.getName() });
				return parse(String.valueOf(text), top);
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
			}
		});
	}

	/** " 1:  123456  98765432  [B (java.base@25)" lines, then "Total  instances  bytes". */
	static Result parse(String text, int top) {
		List<Row> rows = new ArrayList<>();
		long totalBytes = 0;
		long totalInstances = 0;
		for (String line : text.split("\n")) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length >= 3 && parts[0].equals("Total")) {
				totalInstances = Long.parseLong(parts[1]);
				totalBytes = Long.parseLong(parts[2]);
			} else if (parts.length >= 4 && parts[0].endsWith(":") && rows.size() < top) {
				try {
					rows.add(new Row(readable(parts[3]), Long.parseLong(parts[1]), Long.parseLong(parts[2])));
				} catch (NumberFormatException e) {
					// Not a row.
				}
			}
		}
		return new Result(rows, totalBytes, totalInstances);
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
