package eu.explorerseden.nicecontrolcenter.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * English texts of the data packs' own translations (assets/&lt;ns&gt;/lang/en_us.json inside the packs in
 * the world's datapacks folder). The server only knows Minecraft's and the mods' texts, so a message
 * like Fabled Roots' death messages would otherwise read as its fallback (" has fallen.") on Discord.
 */
public final class PackLang {
	private static final Pattern LANG = Pattern.compile("assets/[^/]+/lang/en_us\\.json");
	private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([sd%])");
	private static volatile Map<String, String> texts = Map.of();

	private PackLang() {
	}

	/** At start and after /reload. */
	public static void load(MinecraftServer server) {
		Map<String, String> found = new HashMap<>();
		Path dir = server.getWorldPath(LevelResource.DATAPACK_DIR);
		if (Files.isDirectory(dir)) {
			try (Stream<Path> packs = Files.list(dir)) {
				for (Path pack : packs.sorted().toList()) {
					try {
						if (Files.isDirectory(pack)) folder(pack, found);
						else if (pack.getFileName().toString().endsWith(".zip")) zip(pack, found);
					} catch (IOException | RuntimeException e) {
						NiceControlCenter.LOGGER.debug("Discord: couldn't read the texts of {}: {}", pack, e.toString());
					}
				}
			} catch (IOException e) {
				// No data packs.
			}
		}
		texts = Map.copyOf(found);
		if (!found.isEmpty()) NiceControlCenter.LOGGER.info("Discord: {} texts from data packs for its messages", found.size());
	}

	private static void folder(Path pack, Map<String, String> into) throws IOException {
		Path assets = pack.resolve("assets");
		if (!Files.isDirectory(assets)) return;
		try (Stream<Path> namespaces = Files.list(assets)) {
			for (Path ns : namespaces.toList()) {
				Path file = ns.resolve("lang").resolve("en_us.json");
				if (Files.isRegularFile(file)) try (InputStream in = Files.newInputStream(file)) {
					read(in, into);
				}
			}
		}
	}

	private static void zip(Path pack, Map<String, String> into) throws IOException {
		try (ZipFile zip = new ZipFile(pack.toFile())) {
			for (ZipEntry e : java.util.Collections.list(zip.entries())) {
				if (LANG.matcher(e.getName()).matches()) try (InputStream in = zip.getInputStream(e)) {
					read(in, into);
				}
			}
		}
	}

	private static void read(InputStream in, Map<String, String> into) {
		JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (e.getValue().isJsonPrimitive()) into.putIfAbsent(e.getKey(), e.getValue().getAsString());
		}
	}

	/** The message as plain text, with data pack translations filled in. */
	public static String plain(Component message) {
		StringBuilder out = new StringBuilder();
		append(message, out);
		return out.toString();
	}

	private static void append(Component c, StringBuilder out) {
		if (c.getContents() instanceof TranslatableContents tr && !Language.getInstance().has(tr.getKey()) && texts.containsKey(tr.getKey())) {
			format(texts.get(tr.getKey()), tr.getArgs(), out);
		} else {
			c.getContents().visit((FormattedText.ContentConsumer<Object>) s -> {
				out.append(s);
				return Optional.empty();
			});
		}
		for (Component sibling : c.getSiblings()) append(sibling, out);
	}

	/** %s / %1$s like Minecraft's own formatting; arguments are text components or plain values. */
	private static void format(String pattern, Object[] args, StringBuilder out) {
		Matcher m = ARG.matcher(pattern);
		int next = 0;
		int last = 0;
		while (m.find()) {
			out.append(pattern, last, m.start());
			last = m.end();
			if (m.group(2).equals("%")) {
				out.append('%');
				continue;
			}
			int i = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
			if (i >= 0 && i < args.length) {
				Object a = args[i];
				if (a instanceof Component ac) append(ac, out);
				else out.append(a);
			}
		}
		out.append(pattern.substring(last));
	}

	/** Whether the message (or a part of it) uses a translation key starting with this. */
	public static boolean uses(Component c, String keyPrefix) {
		if (c.getContents() instanceof TranslatableContents tr) {
			if (tr.getKey().startsWith(keyPrefix)) return true;
			for (Object a : tr.getArgs()) if (a instanceof Component ac && uses(ac, keyPrefix)) return true;
		}
		for (Component sibling : c.getSiblings()) if (uses(sibling, keyPrefix)) return true;
		return false;
	}
}
