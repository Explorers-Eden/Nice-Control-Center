package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks a config file's syntax before it's saved, with the line of the first problem. */
final class ConfigCheck {
	record Problem(String message, int line, int column) {
	}

	private static final Pattern GSON_POS = Pattern.compile("line (\\d+) column (\\d+)");

	private ConfigCheck() {
	}

	static String format(String fileName) {
		String n = fileName.toLowerCase(Locale.ROOT);
		if (n.endsWith(".json5") || n.endsWith(".jsonc")) return "json5";
		if (n.endsWith(".json") || n.endsWith(".mcmeta")) return "json";
		if (n.endsWith(".toml")) return "toml";
		if (n.endsWith(".yml") || n.endsWith(".yaml")) return "yaml";
		if (n.endsWith(".properties")) return "properties";
		return "text";
	}

	/** Null when the text is fine (or the format isn't checked). */
	static Problem check(String fileName, String text) {
		try {
			switch (format(fileName)) {
				case "json" -> {
					// Many mods write JSON with comments; strict first, then the lenient reading tells
					// whether it's only comments/trailing commas.
					JsonReader reader = new JsonReader(new StringReader(text));
					reader.setStrictness(Strictness.LEGACY_STRICT);
					JsonParser.parseReader(reader);
					if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return new Problem("Extra text after the end of the JSON.", 0, 0);
				}
				case "json5" -> {
					JsonReader reader = new JsonReader(new StringReader(text));
					reader.setStrictness(Strictness.LENIENT);
					JsonParser.parseReader(reader);
				}
				case "toml" -> {
					TomlParseResult result = Toml.parse(text);
					if (result.hasErrors()) {
						var e = result.errors().getFirst();
						return new Problem(e.getMessage(), e.position() == null ? 0 : e.position().line(), e.position() == null ? 0 : e.position().column());
					}
				}
				case "yaml" -> {
					LoaderOptions options = new LoaderOptions();
					options.setAllowDuplicateKeys(false);
					new Yaml(new SafeConstructor(options)).loadAll(text).forEach(d -> { });
				}
				case "properties" -> new Properties().load(new StringReader(text));
				default -> {
					return null;
				}
			}
			return null;
		} catch (MarkedYAMLException e) {
			Mark mark = e.getProblemMark();
			return new Problem(e.getProblem(), mark == null ? 0 : mark.getLine() + 1, mark == null ? 0 : mark.getColumn() + 1);
		} catch (com.google.gson.JsonParseException e) {
			String msg = e.getCause() instanceof MalformedJsonException m ? m.getMessage() : e.getMessage();
			Matcher pos = GSON_POS.matcher(msg == null ? "" : msg);
			String clean = msg == null ? "Not valid JSON." : msg.replaceAll("\\s*See https://\\S+", "").replaceAll(" at line \\d+ column \\d+ path \\S*", "");
			return pos.find() ? new Problem(clean, Integer.parseInt(pos.group(1)), Integer.parseInt(pos.group(2))) : new Problem(clean, 0, 0);
		} catch (IOException | RuntimeException e) {
			return new Problem(e.getMessage() == null ? e.toString() : e.getMessage(), 0, 0);
		}
	}

	/** Used to tell the UI whether the JSON is strict (form view can rewrite it) or has comments. */
	static boolean strictJsonObjectOfScalars(String text) {
		try {
			JsonReader reader = new JsonReader(new StringReader(text));
			reader.setStrictness(Strictness.LEGACY_STRICT);
			var el = JsonParser.parseReader(reader);
			if (!el.isJsonObject()) return false;
			for (Map.Entry<String, com.google.gson.JsonElement> e : el.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonPrimitive()) return false;
			}
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
