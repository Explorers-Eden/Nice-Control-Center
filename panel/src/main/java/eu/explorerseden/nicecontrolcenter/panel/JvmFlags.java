package eu.explorerseden.nicecontrolcenter.panel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Builds the java command line for the Minecraft server from the settings. */
public final class JvmFlags {
	/** Aikar's G1 flags, the usual choice for Minecraft servers up to ~12 GB. */
	static final List<String> AIKAR = List.of("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
			"-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30",
			"-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5",
			"-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
			"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1");
	/** Generational ZGC (the only ZGC mode since Java 24): very short pauses, best with plenty of memory. */
	static final List<String> ZGC = List.of("-XX:+UseZGC", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem");

	private JvmFlags() {
	}

	public static List<String> command(PanelSettings s) {
		List<String> cmd = new ArrayList<>();
		cmd.add(s.javaPath);
		cmd.add("-Xms" + s.memoryMinMb + "M");
		cmd.add("-Xmx" + s.memoryMaxMb + "M");
		cmd.addAll(switch (s.preset) {
			case "aikar" -> AIKAR;
			case "zgc" -> ZGC;
			default -> List.of();
		});
		cmd.addAll(split(s.customArgs));
		cmd.add("-jar");
		cmd.add(s.serverJar);
		cmd.add("nogui");
		return cmd;
	}

	/** Splits on whitespace, keeping "double quoted" parts together. */
	static List<String> split(String args) {
		List<String> out = new ArrayList<>();
		if (args == null) return out;
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (char c : args.toCharArray()) {
			if (c == '"') quoted = !quoted;
			else if (Character.isWhitespace(c) && !quoted) {
				if (!cur.isEmpty()) out.add(cur.toString());
				cur.setLength(0);
			} else cur.append(c);
		}
		if (!cur.isEmpty()) out.add(cur.toString());
		return out;
	}

	/** "java" from the image plus any JDKs dropped into /opt/java/<name>/bin/java. */
	public static List<String> runtimes() {
		List<String> out = new ArrayList<>();
		out.add("java");
		Path root = Path.of("/opt/java");
		// The image's own JDK also lives there; that one is "java" already.
		Path own = Path.of(System.getProperty("java.home"), "bin", "java");
		if (Files.isDirectory(root)) {
			try (Stream<Path> dirs = Files.list(root)) {
				dirs.map(d -> d.resolve("bin/java")).filter(Files::isExecutable).filter(p -> !p.equals(own))
						.map(Path::toString).sorted().forEach(out::add);
			} catch (IOException e) {
				// Only the default runtime then.
			}
		}
		return out;
	}
}
