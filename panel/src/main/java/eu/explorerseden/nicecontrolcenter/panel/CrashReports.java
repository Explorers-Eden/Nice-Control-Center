package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Reads a crash report for the Discord crash message: what happened (description and exception) and which
 * mod probably caused it. The suspect is the mod of the first stack frame that isn't Minecraft, Java,
 * Fabric or a common library, or the mod named in a mixin handler (handler$…$modid$…).
 */
final class CrashReports {
	record Summary(String what, String mod) {
	}

	private static final List<String> NOT_MODS = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.",
			"net.fabricmc.", "org.spongepowered.", "it.unimi.", "io.netty.", "com.google.", "org.slf4j.", "org.apache.", "joptsimple.",
			"com.llamalad7.", "org.objectweb.");
	private static final Pattern FRAME = Pattern.compile("^\\s*at ([\\w.$]+)\\.([\\w$<>]+)\\(");
	private static final Pattern MIXIN = Pattern.compile("(?:handler|redirect|modify\\w*|wrap\\w*|localvar|inject)\\$[a-z0-9]+\\$([a-z0-9_-]+)\\$");

	private CrashReports() {
	}

	static Summary read(Path report, Path modsDir) {
		List<String> lines;
		try {
			lines = Files.readAllLines(report, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return new Summary("", null);
		}
		String description = "";
		String exception = "";
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.startsWith("Description: ")) {
				description = line.substring("Description: ".length()).strip();
				for (int j = i + 1; j < lines.size(); j++) {
					if (!lines.get(j).isBlank()) {
						exception = lines.get(j).strip();
						break;
					}
				}
				break;
			}
		}
		String what = description + (exception.isEmpty() ? "" : ": " + exception);
		if (what.length() > 300) what = what.substring(0, 300) + "…";
		return new Summary(what, suspect(lines, modsDir));
	}

	private static String suspect(List<String> lines, Path modsDir) {
		for (String line : lines) {
			Matcher frame = FRAME.matcher(line);
			if (!frame.find()) {
				if (line.startsWith("-- ") && !line.startsWith("-- Head --")) break; // Past the main stack trace.
				continue;
			}
			Matcher mixin = MIXIN.matcher(frame.group(2));
			if (mixin.find()) {
				String name = modName(modsDir, mixin.group(1), null);
				return name != null ? name : mixin.group(1);
			}
			String cls = frame.group(1);
			if (NOT_MODS.stream().anyMatch(cls::startsWith)) continue;
			String name = modName(modsDir, null, cls);
			if (name != null) return name;
		}
		return null;
	}

	/** The name of the mod in mods/ with this id, or the one containing this class. */
	private static String modName(Path modsDir, String id, String cls) {
		String entry = cls == null ? null : cls.replaceAll("\\$.*", "").replace('.', '/') + ".class";
		try (Stream<Path> jars = Files.isDirectory(modsDir) ? Files.list(modsDir) : Stream.empty()) {
			for (Path jar : jars.filter(f -> f.getFileName().toString().endsWith(".jar")).toList()) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					var info = zip.getEntry("fabric.mod.json");
					if (info == null) continue;
					JsonObject json;
					try (InputStream in = zip.getInputStream(info)) {
						json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
					}
					boolean match = id != null ? json.has("id") && id.equals(json.get("id").getAsString()) : zip.getEntry(entry) != null;
					if (match) return json.has("name") ? json.get("name").getAsString() : jar.getFileName().toString();
				} catch (IOException | RuntimeException e) {
					// Not a readable mod jar.
				}
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}
}
