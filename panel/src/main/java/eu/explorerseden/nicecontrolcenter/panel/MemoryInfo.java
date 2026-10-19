package eu.explorerseden.nicecontrolcenter.panel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the container's memory goes (Linux only; empty elsewhere): the Minecraft process and the panel as the
 * system counts them (resident memory: heap plus everything outside it), and the container's total from
 * cgroup v2, of which the file cache is memory the kernel gives back whenever something needs it.
 */
final class MemoryInfo {
	private MemoryInfo() {
	}

	static Map<String, Object> read(Long serverPid) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (serverPid != null) put(out, "server", rss(serverPid));
		put(out, "panel", rss(ProcessHandle.current().pid()));
		out.put("panelHeapMax", Runtime.getRuntime().maxMemory());
		put(out, "container", number(Path.of("/sys/fs/cgroup/memory.current")));
		long max = number(Path.of("/sys/fs/cgroup/memory.max"));
		if (max > 0) out.put("containerMax", max);
		try {
			for (String line : Files.readAllLines(Path.of("/sys/fs/cgroup/memory.stat"))) {
				if (line.startsWith("file ")) put(out, "fileCache", Long.parseLong(line.substring(5).strip()));
			}
		} catch (IOException | RuntimeException e) {
			// No cgroup v2 here.
		}
		return out;
	}

	private static void put(Map<String, Object> out, String key, long value) {
		if (value > 0) out.put(key, value);
	}

	/** Resident memory of a process in bytes, or -1. */
	private static long rss(long pid) {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
				if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
			}
		} catch (IOException | RuntimeException e) {
			// Not Linux, or gone.
		}
		return -1;
	}

	private static long number(Path file) {
		try {
			return Long.parseLong(Files.readString(file).strip());
		} catch (IOException | RuntimeException e) {
			// "max" (no limit) or no such file.
			return -1;
		}
	}
}
