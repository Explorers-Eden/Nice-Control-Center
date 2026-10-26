package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Vanilla's server code of conduct: with enable-code-of-conduct=true in server.properties, players see the text from
 * codeofconduct/&lt;language&gt;.txt (their game language, else en_us) when they join and accept it once; a changed text
 * asks again. The server reads it when it starts.
 */
final class CodeOfConduct {
	private static final Pattern LANG = Pattern.compile("[a-z]{2,3}_[a-z]{2,4}");
	private static final String KEY = "enable-code-of-conduct";

	private final Path serverDir;
	private final Audit audit;

	CodeOfConduct(Path serverDir, Audit audit) {
		this.serverDir = serverDir;
		this.audit = audit;
	}

	private Path dir() {
		return serverDir.resolve("codeofconduct");
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/conduct", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, String> texts = new TreeMap<>();
			if (Files.isDirectory(dir())) {
				try (var files = Files.list(dir())) {
					for (Path f : files.toList()) {
						String n = f.getFileName().toString();
						if (n.endsWith(".txt") && LANG.matcher(n.substring(0, n.length() - 4)).matches()) texts.put(n.substring(0, n.length() - 4), Files.readString(f, StandardCharsets.UTF_8));
					}
				}
			}
			String on = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty(KEY, "false");
			json(ctx, Map.of("enabled", Boolean.parseBoolean(on.strip()), "texts", texts));
		}));

		routes.post("/api/conduct", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			JsonObject body = body(ctx);
			Map<String, String> texts = new TreeMap<>();
			if (body.has("texts") && body.get("texts").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : body.getAsJsonObject("texts").entrySet()) {
					String lang = e.getKey().strip().toLowerCase(java.util.Locale.ROOT);
					if (!LANG.matcher(lang).matches()) {
						error(ctx, HttpStatus.BAD_REQUEST, "\"" + e.getKey() + "\" isn't a language code like en_us or de_de.");
						return;
					}
					String text = e.getValue().getAsString().replace("\r\n", "\n");
					if (text.length() > 20_000) {
						error(ctx, HttpStatus.BAD_REQUEST, "The " + lang + " text is longer than 20,000 characters.");
						return;
					}
					if (!text.isBlank()) texts.put(lang, text);
				}
			}
			boolean enabled = Boolean.TRUE.equals(bool(body, "enabled"));
			if (enabled && !texts.containsKey("en_us")) {
				error(ctx, HttpStatus.BAD_REQUEST, "Write at least the English (en_us) text: players in other languages see it when theirs is missing.");
				return;
			}
			Files.createDirectories(dir());
			try (var files = Files.list(dir())) {
				for (Path f : files.toList()) {
					String n = f.getFileName().toString();
					if (n.endsWith(".txt") && !texts.containsKey(n.substring(0, n.length() - 4))) Files.delete(f);
				}
			}
			for (Map.Entry<String, String> t : texts.entrySet()) Files.writeString(dir().resolve(t.getKey() + ".txt"), t.getValue(), StandardCharsets.UTF_8);
			setProperty(KEY, String.valueOf(enabled));
			audit.log(me.name(), "settings.conduct", (enabled ? "on" : "off") + ", " + String.join(", ", texts.keySet()), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}

	/** Changes one line of server.properties, keeping the rest as it is. */
	private void setProperty(String key, String value) throws IOException {
		Path f = serverDir.resolve("server.properties");
		List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
		boolean found = false;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(key + "=")) {
				lines.set(i, key + "=" + value);
				found = true;
			}
		}
		if (!found) lines.add(key + "=" + value);
		Files.write(f, lines, StandardCharsets.ISO_8859_1);
	}
}
