package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;

/**
 * Mob lines as dungeon markers and wave arenas write them: "zombie", "minecraft:skeleton x3",
 * "husk x2 {CustomName:'Guard'}"; several in one text with commas ("husk x6, skeleton x2"). Summoned like the
 * /summon command, so their data works the same everywhere.
 */
public final class MobLines {
	/** One line: the mob, how many, its data ("" or "{…}"). */
	public record Entry(String id, int count, String nbt) {
	}

	private MobLines() {
	}

	/** One line; null when there's no mob in it. */
	public static Entry parse(String line) {
		String text = line == null ? "" : line.strip();
		int brace = text.indexOf('{');
		String nbt = brace >= 0 ? text.substring(brace).strip() : "";
		String[] words = (brace >= 0 ? text.substring(0, brace) : text).strip().split("\\s+");
		if (words.length == 0 || words[0].isEmpty()) return null;
		String id = words[0].contains(":") ? words[0] : "minecraft:" + words[0];
		int count = 1;
		for (String w : words) if (w.matches("[xX]\\d{1,2}")) count = Math.max(1, Math.min(32, Integer.parseInt(w.substring(1))));
		return new Entry(id.toLowerCase(Locale.ROOT), count, nbt);
	}

	/** Several lines split at commas (not the ones inside {…}, [...] or quotes). */
	public static List<Entry> parseList(String text) {
		List<Entry> out = new ArrayList<>();
		if (text == null) return out;
		int depth = 0;
		char quote = 0;
		StringBuilder cur = new StringBuilder();
		for (char ch : text.toCharArray()) {
			if (quote != 0) {
				if (ch == quote) quote = 0;
			} else if (ch == '"' || ch == '\'') {
				quote = ch;
			} else if (ch == '{' || ch == '[') {
				depth++;
			} else if (ch == '}' || ch == ']') {
				depth = Math.max(0, depth - 1);
			} else if (ch == ',' && depth == 0) {
				Entry e = parse(cur.toString());
				if (e != null) out.add(e);
				cur.setLength(0);
				continue;
			}
			cur.append(ch);
		}
		Entry e = parse(cur.toString());
		if (e != null) out.add(e);
		return out;
	}

	/** Summons one mob of the entry (count is up to the caller), kept from despawning, with extra data (e.g. tags). */
	public static void summon(MinecraftServer server, String dim, double x, double y, double z, Entry e, String extra) {
		String own = "PersistenceRequired:1b" + (extra == null || extra.isEmpty() ? "" : "," + extra);
		String data = e.nbt().isEmpty() ? "{" + own + "}" : "{" + own + "," + e.nbt().substring(1);
		ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon %s %.2f %.2f %.2f %s", dim, e.id(), x, y, z, data));
	}
}
