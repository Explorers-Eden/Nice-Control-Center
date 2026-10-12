package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How Discord messages look in game, written like DiscordJustSync / MiniMessage:
 * {@code <aqua>Discord</aqua> <dark_gray>┃</dark_gray> %user%: %msg%}. Turned into Minecraft text parts
 * (tellraw JSON). Colours by name or {@code <#rrggbb>}, {@code <bold>}/{@code <b>}, {@code <italic>}/{@code <i>},
 * {@code <underlined>}/{@code <u>}, {@code <strikethrough>}/{@code <st>}, {@code <reset>}. Placeholders
 * %user% / {user} and %msg% / {message} are filled with plain text, so tags in a message stay text.
 */
final class GameFormat {
	static final String DEFAULT = "<aqua>Discord</aqua> <dark_gray>┃</dark_gray> %user%: %msg%";

	private static final Set<String> COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
			"dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
	private static final Map<String, String> ALIASES = Map.of("grey", "gray", "dark_grey", "dark_gray", "purple", "light_purple", "pink", "light_purple",
			"orange", "gold");
	private static final Map<String, String> DECORATIONS = Map.ofEntries(Map.entry("bold", "bold"), Map.entry("b", "bold"), Map.entry("italic", "italic"),
			Map.entry("i", "italic"), Map.entry("em", "italic"), Map.entry("underlined", "underlined"), Map.entry("u", "underlined"),
			Map.entry("strikethrough", "strikethrough"), Map.entry("st", "strikethrough"), Map.entry("obfuscated", "obfuscated"), Map.entry("obf", "obfuscated"));
	private static final Pattern TOKEN = Pattern.compile("<(/?)([#a-zA-Z_:0-9]+)>|%user%|%msg%|%attachments%|\\{user}|\\{message}");

	private record Style(String color, Set<String> decorations) {
	}

	private GameFormat() {
	}

	/** Returns an error for a format that can't be used, or null. */
	static String check(String format) {
		if (format == null || format.isBlank()) return "The format can't be empty.";
		if (format.length() > 300) return "The format can be up to 300 characters.";
		if (!format.contains("%msg%") && !format.contains("{message}")) return "Put %msg% where the message goes.";
		return null;
	}

	/** The format with the sender and message filled in, as tellraw parts (starting with an empty one, so styles don't leak). */
	static JsonArray render(String format, String user, String message) {
		JsonArray out = new JsonArray();
		out.add("");
		Deque<Style> stack = new ArrayDeque<>();
		stack.push(new Style(null, Set.of()));
		String text = format == null || format.isBlank() ? DEFAULT : format;
		Matcher m = TOKEN.matcher(text);
		int at = 0;
		while (m.find()) {
			add(out, text.substring(at, m.start()), stack.peek());
			at = m.end();
			String token = m.group();
			switch (token) {
				case "%user%", "{user}" -> add(out, user, stack.peek());
				case "%msg%", "{message}" -> add(out, message, stack.peek());
				case "%attachments%" -> { }
				default -> {
					boolean closing = !m.group(1).isEmpty();
					String tag = m.group(2).toLowerCase(Locale.ROOT);
					if (tag.equals("reset")) {
						stack.clear();
						stack.push(new Style(null, Set.of()));
					} else if (closing) {
						if (stack.size() > 1) stack.pop();
					} else {
						Style top = stack.peek();
						String color = colour(tag);
						String deco = DECORATIONS.get(tag);
						if (color != null) stack.push(new Style(color, top.decorations()));
						else if (deco != null) {
							java.util.Set<String> d = new java.util.HashSet<>(top.decorations());
							d.add(deco);
							stack.push(new Style(top.color(), Set.copyOf(d)));
						} else {
							// Not a tag we know: keep it as text.
							add(out, token, stack.peek());
						}
					}
				}
			}
		}
		add(out, text.substring(at), stack.peek());
		return out;
	}

	private static String colour(String tag) {
		String t = tag.startsWith("color:") ? tag.substring(6) : tag;
		if (t.matches("#[0-9a-f]{6}")) return t;
		t = ALIASES.getOrDefault(t, t);
		return COLORS.contains(t) ? t : null;
	}

	private static void add(JsonArray out, String text, Style style) {
		if (text == null || text.isEmpty()) return;
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		o.addProperty("color", style.color() == null ? "white" : style.color());
		for (String d : style.decorations()) o.addProperty(d, true);
		out.add(o);
	}
}
