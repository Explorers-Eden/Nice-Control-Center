package eu.explorerseden.nicecontrolcenter.packs;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.core.SourceIndex;

/**
 * Data pack settings, read from the packs' own in-game settings dialogs.
 *
 * <p>Explorer's Eden packs keep their settings in the command storage {@code eden:settings} (others,
 * like Katters Structures, in a storage of their own) and open a settings dialog with
 * {@code function <ns>:dialog/... with storage <storage> <path>}. That dialog describes every setting
 * (label, type, options, range) and its "Confirm" button runs a command template. This class reads
 * those dialogs, shows the current values, and applies changes by running the same command the
 * dialog would, so the pack's own follow-up work still happens.
 */
public final class PackSettings {
	private static final Pattern CALL = Pattern.compile(
			"function\\s+([a-z0-9_.-]+:[a-z0-9_/.-]+)\\s+with\\s+storage\\s+([a-z0-9_.-]+:[a-z0-9_/.-]+)\\s+([A-Za-z0-9_.]+)");
	private static final Pattern VAR = Pattern.compile("\\$\\(([A-Za-z0-9_]+)\\)");

	/** One setting in a dialog. {@code value} is the current value as the dialog would send it. */
	public record Setting(String key, String type, String label, String value, List<Option> options, Double min, Double max,
			Double step, String onTrue, String onFalse, Integer maxLength) {
	}

	public record Option(String id, String label) {
	}

	/** One settings dialog of a pack. */
	public record Dialog(String id, String pack, String title, String section, String storagePath, List<Setting> settings) {
	}

	/**
	 * A step in a pack's settings menu, mirroring the in-game dialogs: a menu ({@code dialog == null})
	 * with children, or a settings form ({@code dialog} = the {@link Dialog} id).
	 */
	public record Node(String label, String description, String dialog, List<Node> children) {
	}

	/** One project with its menu tree. */
	public record PackTree(String pack, List<Node> children) {
	}

	/** How to rebuild a dialog: kept server-side only. */
	private record Definition(String id, String pack, String title, Identifier storage, String storagePath, String function,
			JsonArray inputs, String template) {
	}

	/** Dialog id for a "function … with storage … path" call. */
	private static String callId(Matcher call) {
		return call.group(1) + "@" + call.group(2) + "/" + call.group(3);
	}

	private static volatile List<Definition> definitions;
	/** English texts from the packs' own lang files (assets/<ns>/lang/en_us.json). */
	private static volatile Map<String, String> lang = Map.of();
	private static volatile List<PackTree> trees;

	private PackSettings() {
	}

	/** Forget everything after /reload; the next request scans again. */
	public static void clear() {
		definitions = null;
		trees = null;
	}

	/**
	 * Each pack's settings menus as a tree, built from its dialogs: a menu button that opens another
	 * menu becomes a branch, one that opens a settings form becomes a leaf. Buttons that don't lead to
	 * settings (give items, wiki pages, teleports) are left out. Server thread.
	 */
	public static List<PackTree> tree(MinecraftServer server) {
		List<PackTree> cached = trees;
		if (cached != null) {
			return cached;
		}
		Map<String, Definition> byId = new LinkedHashMap<>();
		definitions(server).forEach(d -> byId.put(d.id(), d));
		Map<String, JsonObject> dialogs = new LinkedHashMap<>();
		server.getResourceManager().listResources("dialog", id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
			String content = read(resource);
			if (content == null) {
				return;
			}
			try {
				String path = id.getPath().substring("dialog/".length(), id.getPath().length() - 5);
				dialogs.put(id.getNamespace() + ":" + path, JsonParser.parseString(content).getAsJsonObject());
			} catch (RuntimeException e) {
				// Not a readable dialog; skipped.
			}
		});
		Set<String> referenced = new java.util.HashSet<>();
		dialogs.values().forEach(d -> forEachAction(d, (label, tooltip, action) -> {
			if (str(action, "type").endsWith("show_dialog") && action.has("dialog") && action.get("dialog").isJsonPrimitive()) {
				referenced.add(action.get("dialog").getAsString());
			}
		}));

		Map<String, List<Node>> byPack = new LinkedHashMap<>();
		Set<String> reached = new java.util.HashSet<>();
		for (Map.Entry<String, JsonObject> root : dialogs.entrySet()) {
			if (referenced.contains(root.getKey()) || !str(root.getValue(), "type").endsWith("multi_action")) {
				continue;
			}
			List<Node> children = menu(root.getValue(), dialogs, byId, reached, new java.util.HashSet<>(Set.of(root.getKey())));
			if (children.isEmpty()) {
				continue;
			}
			String pack = SourceIndex.namespace(root.getKey().substring(0, root.getKey().indexOf(':'))).name();
			String title = text(root.getValue().get("title"), root.getKey());
			byPack.computeIfAbsent(pack, k -> new ArrayList<>()).add(new Node(title, null, null, children));
		}
		// Forms no settings menu leads to are left out on purpose: those are in-game tools that fill
		// their storage from what the player is looking at (e.g. editing a text display), not settings.
		List<PackTree> result = new ArrayList<>();
		byPack.forEach((pack, roots) -> result.add(new PackTree(pack, roots.size() == 1 ? roots.get(0).children() : roots)));
		result.sort((a, b) -> a.pack().compareToIgnoreCase(b.pack()));
		trees = result;
		return result;
	}

	/** Whether a pack's settings menu leads to this form (in-game tools are left out). */
	private static boolean inMenus(MinecraftServer server, String dialogId) {
		java.util.ArrayDeque<Node> todo = new java.util.ArrayDeque<>();
		tree(server).forEach(t -> todo.addAll(t.children()));
		while (!todo.isEmpty()) {
			Node node = todo.poll();
			if (dialogId.equals(node.dialog())) {
				return true;
			}
			todo.addAll(node.children());
		}
		return false;
	}

	private interface ActionVisitor {
		void visit(JsonElement label, JsonElement tooltip, JsonObject action);
	}

	private static void forEachAction(JsonObject dialog, ActionVisitor visitor) {
		JsonArray actions = dialog.has("actions") && dialog.get("actions").isJsonArray() ? dialog.getAsJsonArray("actions") : new JsonArray();
		for (JsonElement element : actions) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject button = element.getAsJsonObject();
			if (button.has("action") && button.get("action").isJsonObject()) {
				visitor.visit(button.get("label"), button.get("tooltip"), button.getAsJsonObject("action"));
			}
		}
	}

	/** The children of a menu dialog that lead to settings. */
	private static List<Node> menu(JsonObject dialog, Map<String, JsonObject> dialogs, Map<String, Definition> byId, Set<String> reached,
			Set<String> path) {
		List<Node> children = new ArrayList<>();
		forEachAction(dialog, (labelJson, tooltipJson, action) -> {
			String type = str(action, "type");
			String label = text(labelJson, "").strip();
			String tooltip = tooltipJson == null ? null : text(tooltipJson, null);
			if (type.endsWith("show_dialog") && action.has("dialog")) {
				JsonElement target = action.get("dialog");
				JsonObject next = target.isJsonObject() ? target.getAsJsonObject() : dialogs.get(target.getAsString());
				String key = target.isJsonPrimitive() ? target.getAsString() : null;
				if (next == null || (key != null && path.contains(key))) {
					return;
				}
				Set<String> deeper = new java.util.HashSet<>(path);
				if (key != null) {
					deeper.add(key);
				}
				List<Node> sub = menu(next, dialogs, byId, reached, deeper);
				if (!sub.isEmpty()) {
					children.add(new Node(label.isEmpty() ? text(next.get("title"), "Menu") : label, tooltip, null, sub));
				}
			} else if (type.endsWith("run_command") && action.has("command")) {
				Matcher matcher = CALL.matcher(str(action, "command"));
				if (matcher.find()) {
					String id = callId(matcher);
					if (byId.containsKey(id)) {
						reached.add(id);
						children.add(new Node(label.isEmpty() ? byId.get(id).title() : label, tooltip, id, List.of()));
					}
				}
			}
		});
		return children;
	}

	/** All settings dialogs with current values. Server thread. */
	public static List<Dialog> list(MinecraftServer server) {
		List<Dialog> result = new ArrayList<>();
		for (Definition definition : definitions(server)) {
			CompoundTag data = at(server.getCommandStorage().get(definition.storage()), definition.storagePath());
			if (data == null) {
				continue;
			}
			List<Setting> list = new ArrayList<>();
			for (JsonElement element : definition.inputs()) {
				Setting setting = element.isJsonObject() ? setting(element.getAsJsonObject(), data) : null;
				if (setting != null) {
					list.add(setting);
				}
			}
			if (!list.isEmpty()) {
				result.add(new Dialog(definition.id(), definition.pack(), definition.title(), section(definition), definition.storagePath(), list));
			}
		}
		return result;
	}

	/**
	 * Applies new values for one dialog by running its confirm command. Returns an error message, or
	 * null on success. Server thread.
	 */
	public static String apply(MinecraftServer server, String dialogId, Map<String, String> values, String who) {
		Definition definition = definitions(server).stream().filter(d -> d.id().equals(dialogId)).findFirst().orElse(null);
		if (definition == null || !inMenus(server, dialogId)) {
			return "Unknown settings dialog.";
		}
		CompoundTag data = at(server.getCommandStorage().get(definition.storage()), definition.storagePath());
		if (data == null) {
			return "The data pack hasn't stored its settings yet.";
		}
		Map<String, String> args = new LinkedHashMap<>();
		List<String> changes = new ArrayList<>();
		for (JsonElement element : definition.inputs()) {
			Setting setting = element.isJsonObject() ? setting(element.getAsJsonObject(), data) : null;
			if (setting == null) {
				continue;
			}
			String value = values.getOrDefault(setting.key(), setting.value());
			// Only what was changed is checked; values the pack stored itself are passed on as they are.
			String error = value.equals(setting.value()) ? null : validate(setting, value);
			if (error != null) {
				return setting.label() + ": " + error;
			}
			if (!value.equals(setting.value())) {
				changes.add(setting.label() + ": " + setting.value() + " → " + value);
			}
			args.put(setting.key(), value);
		}
		// Like the game: the dialog function is a macro, so every $(x) in its command is first filled
		// from the settings storage (e.g. $(command_template_misc) becomes a whole command). The
		// placeholders that brings in are then filled with the form's values when "Confirm" runs it.
		Matcher stored = VAR.matcher(definition.template());
		StringBuilder expanded = new StringBuilder();
		while (stored.find()) {
			Tag tag = data.get(stored.group(1));
			String value = tag == null ? stored.group() : tag.asString().orElseGet(() -> tag.asNumber().map(PackSettings::numberText).orElse(tag.toString()));
			stored.appendReplacement(expanded, Matcher.quoteReplacement(value));
		}
		stored.appendTail(expanded);
		String template = expanded.toString();
		Matcher matcher = VAR.matcher(template);
		StringBuilder command = new StringBuilder();
		while (matcher.find()) {
			String value = args.get(matcher.group(1));
			if (value == null) {
				return "The dialog's command uses a value it doesn't ask for (" + matcher.group(1) + ").";
			}
			matcher.appendReplacement(command, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(command);
		String text = command.toString().trim();
		if (text.startsWith("/")) {
			text = text.substring(1);
		}
		if (text.isEmpty()) {
			return "The dialog has no command to run.";
		}
		if (changes.isEmpty()) {
			return null;
		}
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), text);
		NiceControlCenter.LOGGER.info("Dashboard ({}) changed {} settings: {}", who, definition.pack(), String.join(", ", changes));
		return null;
	}

	private static String validate(Setting setting, String value) {
		switch (setting.type()) {
			case "single_option" -> {
				if (setting.options().stream().noneMatch(o -> o.id().equals(value))) {
					return "not one of the choices";
				}
			}
			case "boolean" -> {
				if (!value.equals(setting.onTrue()) && !value.equals(setting.onFalse())) {
					return "must be on or off";
				}
			}
			case "number_range" -> {
				try {
					double number = Double.parseDouble(value);
					if (setting.min() != null && number < setting.min() - 1e-9 || setting.max() != null && number > setting.max() + 1e-9) {
						return "must be between " + format(setting.min()) + " and " + format(setting.max());
					}
					// The in-game slider only stops on steps; other values can confuse the pack.
					if (setting.step() != null && setting.step() > 0) {
						double steps = (number - (setting.min() == null ? 0 : setting.min())) / setting.step();
						if (Math.abs(steps - Math.rint(steps)) > 1e-6) {
							return "must be in steps of " + format(setting.step());
						}
					}
				} catch (NumberFormatException e) {
					return "not a number";
				}
			}
			case "text" -> {
				int max = setting.maxLength() == null ? 256 : setting.maxLength();
				if (value.length() > max) {
					return "can be at most " + max + " characters";
				}
				// Packs put text into their command between quotes, so these would break it.
				if (value.contains("\n") || value.contains("'") || value.contains("\\")) {
					return "can't contain ' or \\ or line breaks";
				}
			}
			default -> {
				return "can't be changed here";
			}
		}
		return null;
	}

	/** A short name for what a dialog configures, from its storage path or function name ("Breeding", "Costs"). */
	private static String section(Definition definition) {
		String path = definition.storagePath();
		String name = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1)
				: definition.function().substring(definition.function().lastIndexOf('/') + 1);
		if (name.equals("config") || name.equals("exec") || name.equals("init") || name.equals(path)) {
			return "Settings";
		}
		return prettify(name);
	}

	// ── Reading values ──────────────────────────────────────────────────────

	private static Setting setting(JsonObject input, CompoundTag data) {
		String type = str(input, "type").replace("minecraft:", "");
		String key = str(input, "key");
		if (key.isEmpty()) {
			return null;
		}
		String label = text(input.get("label"), key);
		switch (type) {
			case "number_range" -> {
				Double start = num(input, "start");
				Double end = num(input, "end");
				Double step = num(input, "step");
				String value = resolve(input.get("initial"), data);
				if (value == null || value.isEmpty()) {
					value = start == null ? "0" : format(start);
				}
				return new Setting(key, type, label, cleanNumber(value, step), List.of(), start, end, step, null, null, null);
			}
			case "single_option" -> {
				List<Option> options = new ArrayList<>();
				String selected = null;
				for (JsonElement element : input.getAsJsonArray("options")) {
					// A trailing comma ("},]") reads as null in lenient JSON.
					if (element.isJsonNull()) {
						continue;
					}
					JsonObject option = element.isJsonObject() ? element.getAsJsonObject() : null;
					String id = option == null ? element.getAsString() : str(option, "id");
					options.add(new Option(id, option == null ? id : text(option.get("display"), id)));
					if (option != null && selected == null && isTrue(resolve(option.get("initial"), data))) {
						selected = id;
					}
				}
				if (options.isEmpty()) {
					return null;
				}
				return new Setting(key, type, label, selected != null ? selected : options.get(0).id(), options, null, null, null, null, null, null);
			}
			case "boolean" -> {
				String onTrue = input.has("on_true") ? str(input, "on_true") : "true";
				String onFalse = input.has("on_false") ? str(input, "on_false") : "false";
				boolean on = isTrue(resolve(input.get("initial"), data));
				return new Setting(key, type, label, on ? onTrue : onFalse, List.of(), null, null, null, onTrue, onFalse, null);
			}
			case "text" -> {
				String value = resolve(input.get("initial"), data);
				Double maxLength = num(input, "max_length");
				return new Setting(key, type, label, value == null ? "" : value, List.of(), null, null, null, null, null,
						maxLength == null ? 256 : maxLength.intValue());
			}
			default -> {
				return null;
			}
		}
	}

	/** An "initial" field: either a placeholder for a storage value, or a literal. */
	private static String resolve(JsonElement element, CompoundTag data) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		String raw = element.isJsonPrimitive() ? element.getAsString() : element.toString();
		Matcher matcher = VAR.matcher(raw);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			Tag tag = data.get(matcher.group(1));
			String value = tag == null ? "" : tag.asString().orElseGet(() -> tag.asNumber().map(PackSettings::numberText).orElse(tag.toString()));
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** An SNBT boolean as the dialog would read it: "true", or a byte stored with "set value true" (1). */
	private static boolean isTrue(String value) {
		return "true".equals(value) || "1".equals(value);
	}

	private static String numberText(Number number) {
		double d = number.doubleValue();
		return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
	}

	private static String cleanNumber(String value, Double step) {
		try {
			double d = Double.parseDouble(value);
			boolean whole = step == null || step == Math.rint(step);
			return whole && d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
		} catch (NumberFormatException e) {
			return value;
		}
	}

	private static String format(Double d) {
		return d == null ? "" : numberText(d);
	}

	private static CompoundTag at(CompoundTag root, String path) {
		CompoundTag current = root;
		for (String part : path.split("\\.")) {
			if (current == null) {
				return null;
			}
			current = current.getCompound(part).orElse(null);
		}
		return current;
	}

	// ── Finding the dialogs ─────────────────────────────────────────────────

	private static List<Definition> definitions(MinecraftServer server) {
		List<Definition> cached = definitions;
		if (cached == null) {
			cached = scan(server);
			definitions = cached;
		}
		return cached;
	}

	/** Finds every "function … with storage …" call and parses the dialog it opens. */
	private static List<Definition> scan(MinecraftServer server) {
		lang = loadLang(server);
		Map<String, String[]> calls = new LinkedHashMap<>();
		Map<Identifier, Resource> files = new LinkedHashMap<>();
		files.putAll(server.getResourceManager().listResources("function", id -> id.getPath().endsWith(".mcfunction")));
		files.putAll(server.getResourceManager().listResources("dialog", id -> id.getPath().endsWith(".json")));
		for (Resource resource : files.values()) {
			String content = read(resource);
			if (content == null || !content.contains("with storage")) {
				continue;
			}
			Matcher matcher = CALL.matcher(content);
			while (matcher.find()) {
				// Scratch storages (eden:temp, kattersstructures:temp) feed in-game tools, not settings.
				if (!matcher.group(2).endsWith(":temp")) {
					calls.putIfAbsent(callId(matcher), new String[] {matcher.group(1), matcher.group(2), matcher.group(3)});
				}
			}
		}

		List<Definition> result = new ArrayList<>();
		for (String[] parts : calls.values()) {
			Identifier function = Identifier.tryParse(parts[0]);
			Identifier storage = Identifier.tryParse(parts[1]);
			if (function == null || storage == null) {
				continue;
			}
			Optional<Resource> file = server.getResourceManager().getResource(
					Identifier.fromNamespaceAndPath(function.getNamespace(), "function/" + function.getPath() + ".mcfunction"));
			if (file.isEmpty()) {
				continue;
			}
			Definition definition = parseDialog(function, storage, parts[2], read(file.get()));
			if (definition != null) {
				result.add(definition);
			}
		}
		result.sort((a, b) -> (a.pack() + " " + a.storagePath()).compareToIgnoreCase(b.pack() + " " + b.storagePath()));
		return result;
	}

	private static Definition parseDialog(Identifier function, Identifier storage, String storagePath, String source) {
		if (source == null) {
			return null;
		}
		// Join continuation lines and drop the macro "$" marker.
		StringBuilder joined = new StringBuilder();
		for (String line : source.split("\\R")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("#")) {
				continue;
			}
			if (trimmed.startsWith("$")) {
				trimmed = trimmed.substring(1);
			}
			if (trimmed.endsWith("\\")) {
				joined.append(trimmed, 0, trimmed.length() - 1);
			} else {
				joined.append(trimmed).append('\n');
			}
		}
		int show = joined.indexOf("dialog show");
		if (show < 0) {
			return null;
		}
		int brace = joined.indexOf("{", show);
		if (brace < 0) {
			return null;
		}
		String json = quotePlaceholders(joined.substring(brace, matchingBrace(joined, brace) + 1));
		JsonObject dialog;
		try {
			JsonReader reader = new JsonReader(new java.io.StringReader(json));
			reader.setStrictness(Strictness.LENIENT);
			dialog = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			NiceControlCenter.LOGGER.debug("Could not read settings dialog {}", function, e);
			return null;
		}
		JsonArray inputs = dialog.getAsJsonArray("inputs");
		String template = template(dialog);
		if (inputs == null || inputs.isEmpty() || template == null) {
			return null;
		}
		String title = text(dialog.get("title"), function.toString());
		String pack = SourceIndex.namespace(function.getNamespace()).name();
		return new Definition(function + "@" + storage + "/" + storagePath, pack, title, storage, storagePath, function.toString(), inputs,
				template);
	}

	/** The command run by the confirm button (confirmation dialogs) or the first dynamic action. */
	private static String template(JsonObject dialog) {
		for (String button : new String[] {"yes", "action"}) {
			JsonObject object = dialog.has(button) && dialog.get(button).isJsonObject() ? dialog.getAsJsonObject(button) : null;
			JsonObject action = object == null ? null : object.has("action") ? object.getAsJsonObject("action") : null;
			if (action != null && str(action, "type").endsWith("dynamic/run_command")) {
				return str(action, "template");
			}
		}
		return null;
	}

	/** {@code "initial":$(x)} isn't valid JSON; wrap bare placeholders in quotes. */
	private static String quotePlaceholders(String json) {
		StringBuilder out = new StringBuilder();
		boolean inString = false;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
				inString = !inString;
			}
			if (!inString && c == '$' && i + 1 < json.length() && json.charAt(i + 1) == '(') {
				int end = json.indexOf(')', i);
				if (end > 0) {
					out.append('"').append(json, i, end + 1).append('"');
					i = end;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}

	private static int matchingBrace(CharSequence text, int open) {
		int depth = 0;
		boolean inString = false;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' && text.charAt(i - 1) != '\\') {
				inString = !inString;
			} else if (!inString && c == '{') {
				depth++;
			} else if (!inString && c == '}' && --depth == 0) {
				return i;
			}
		}
		return text.length() - 1;
	}

	/** en_us texts shipped inside the enabled data packs (their assets folder). */
	private static Map<String, String> loadLang(MinecraftServer server) {
		Map<String, String> result = new java.util.HashMap<>();
		for (net.minecraft.server.packs.repository.Pack pack : server.getPackRepository().getSelectedPacks()) {
			try (java.util.stream.Stream<net.minecraft.server.packs.PackResources> resources = eu.explorerseden.nicecontrolcenter.compat.Packs.open(pack)) {
				resources.forEach(r -> {
					try (r) {
						for (String ns : r.getNamespaces(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)) {
							var supplier = r.getResource(net.minecraft.server.packs.PackType.CLIENT_RESOURCES,
									Identifier.fromNamespaceAndPath(ns, "lang/en_us.json"));
							if (supplier != null) {
								try (java.io.InputStream in = supplier.get()) {
									net.minecraft.locale.Language.loadFromJson(in, result::put);
								} catch (IOException | RuntimeException e) {
									// Broken lang file; keys fall back to their built-in text.
								}
							}
						}
					}
				});
			} catch (RuntimeException e) {
				// A pack that can't be opened just has no texts here.
			}
		}
		return result;
	}

	private static String read(Resource resource) {
		try (BufferedReader reader = resource.openAsReader()) {
			StringBuilder out = new StringBuilder();
			char[] buffer = new char[8192];
			int n;
			while ((n = reader.read(buffer)) > 0) {
				out.append(buffer, 0, n);
			}
			return out.toString();
		} catch (IOException e) {
			return null;
		}
	}

	private static String text(JsonElement element, String fallback) {
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		if (element.isJsonArray()) {
			StringBuilder out = new StringBuilder();
			for (JsonElement part : element.getAsJsonArray()) {
				out.append(text(part, ""));
			}
			return out.length() == 0 ? fallback : out.toString();
		}
		JsonObject object = element.getAsJsonObject();
		if (object.has("translate")) {
			String key = object.get("translate").getAsString();
			String own = lang.get(key);
			if (own != null) {
				return own;
			}
			if (object.has("fallback")) {
				return object.get("fallback").getAsString();
			}
			net.minecraft.locale.Language vanilla = net.minecraft.locale.Language.getInstance();
			return vanilla.has(key) ? vanilla.getOrDefault(key) : prettify(key);
		}
		if (object.has("fallback")) {
			return object.get("fallback").getAsString();
		}
		if (object.has("text")) {
			return object.get("text").getAsString();
		}
		return fallback;
	}

	private static String prettify(String key) {
		String last = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
		return last.isEmpty() ? key : Character.toUpperCase(last.charAt(0)) + last.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String str(JsonObject object, String key) {
		JsonElement e = object.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	private static Double num(JsonObject object, String key) {
		JsonElement e = object.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return null;
		}
		try {
			return Double.parseDouble(e.getAsString());
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
