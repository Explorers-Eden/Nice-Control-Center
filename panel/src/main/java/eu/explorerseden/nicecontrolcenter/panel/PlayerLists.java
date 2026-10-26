package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Whitelist, operators and bans. While the server runs, changes go through its console (whitelist add,
 * op, ban, pardon …) so the game applies them right away and writes its own files; while it's stopped,
 * the vanilla JSON files are edited directly, with UUIDs from Mojang (or the offline UUID when the
 * server runs with online-mode=false).
 */
final class PlayerLists {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Pattern IPV4 = Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

	/** A real IPv4 (four numbers 0–255) or IPv6 address, nothing else. */
	static boolean validIp(String s) {
		if (s == null || s.isEmpty() || s.length() > 45) return false;
		if (IPV4.matcher(s).matches()) return true;
		if (!s.contains(":") || !s.matches("[0-9a-fA-F:.]+")) return false;
		try {
			return java.net.InetAddress.getByName(s) instanceof java.net.Inet6Address;
		} catch (java.net.UnknownHostException e) {
			return false;
		}
	}
	private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

	private final Path dir;
	private final Supervisor server;
	private final Audit audit;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	PlayerLists(Path serverDir, Supervisor server, Audit audit) {
		this.dir = serverDir;
		this.server = server;
		this.audit = audit;
	}

	private JsonArray read(String file) {
		try {
			Path f = dir.resolve(file);
			if (!Files.exists(f)) return new JsonArray();
			JsonElement e = JsonParser.parseString(Files.readString(f));
			return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
		} catch (IOException | RuntimeException e) {
			return new JsonArray();
		}
	}

	private void write(String file, JsonArray list) throws IOException {
		Path f = dir.resolve(file);
		Path tmp = f.resolveSibling(file + ".tmp");
		Files.writeString(tmp, eu.explorerseden.nicecontrolcenter.Json.GSON.newBuilder().setPrettyPrinting().create().toJson(list), StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private Properties properties() {
		return ServerTuning.readProperties(dir.resolve("server.properties"));
	}

	Map<String, Object> view() {
		Properties p = properties();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", server.running());
		out.put("whitelistOn", Boolean.parseBoolean(p.getProperty("white-list", "false")));
		out.put("enforce", Boolean.parseBoolean(p.getProperty("enforce-whitelist", "false")));
		out.put("onlineMode", Boolean.parseBoolean(p.getProperty("online-mode", "true")));
		out.put("opLevel", p.getProperty("op-permission-level", "4"));
		out.put("whitelist", read("whitelist.json"));
		out.put("ops", read("ops.json"));
		out.put("bans", read("banned-players.json"));
		out.put("ipBans", read("banned-ips.json"));
		return out;
	}

	/** The player's UUID: from Mojang on online-mode servers, the offline UUID otherwise. */
	private String uuid(String name) throws IOException {
		if (!Boolean.parseBoolean(properties().getProperty("online-mode", "true"))) {
			return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
		}
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
					.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 404 || r.statusCode() == 204) throw new IOException("There's no Minecraft account called " + name + ".");
			if (r.statusCode() != 200) throw new IOException("Mojang's name lookup answered " + r.statusCode() + "; try again in a moment.");
			String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
			return id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		}
	}

	private static boolean same(JsonElement e, String name) {
		JsonObject o = e.getAsJsonObject();
		return o.has("name") && o.get("name").getAsString().equalsIgnoreCase(name);
	}

	private JsonObject entry(String name) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", uuid(name));
		o.addProperty("name", name);
		return o;
	}

	/** Returns an error, or null. action: whitelist-add/remove, op/deop, ban/pardon, ban-ip/pardon-ip, kick, whitelist-on/off. */
	String apply(String action, String rawTarget, String reason, int level, String by) throws IOException {
		boolean ip = action.endsWith("-ip");
		String target = action.startsWith("whitelist-o") ? "" : rawTarget;
		if (!target.isEmpty() || !action.startsWith("whitelist-o")) {
			if (ip ? !validIp(target) : !NAME.matcher(target).matches()) return ip ? "That isn't an IP address." : "Minecraft names are 1–16 letters, digits or _.";
		}
		reason = reason == null ? "" : reason.replaceAll("[\\r\\n]", " ").strip();
		if (reason.length() > 200) reason = reason.substring(0, 200);
		String note = action + (target.isEmpty() ? "" : " " + target) + (reason.isEmpty() ? "" : " (" + reason + ")");
		if (server.running()) {
			String cmd = switch (action) {
				case "whitelist-add" -> "whitelist add " + target;
				case "whitelist-remove" -> "whitelist remove " + target;
				case "whitelist-on" -> "whitelist on";
				case "whitelist-off" -> "whitelist off";
				case "op" -> "op " + target;
				case "deop" -> "deop " + target;
				case "ban" -> "ban " + target + (reason.isEmpty() ? "" : " " + reason);
				case "pardon" -> "pardon " + target;
				case "ban-ip" -> "ban-ip " + target + (reason.isEmpty() ? "" : " " + reason);
				case "pardon-ip" -> "pardon-ip " + target;
				case "kick" -> "kick " + target + (reason.isEmpty() ? "" : " " + reason);
				default -> null;
			};
			if (cmd == null) return "Unknown action.";
			if (!server.quietCommand(cmd)) return "The server didn't take the command.";
			audit.log(by, "players." + action, note + " (console)", null);
			return null;
		}
		if (action.equals("kick")) return "Nobody is online while the server is stopped.";
		String created = ZonedDateTime.now().format(CREATED);
		switch (action) {
			case "whitelist-add" -> {
				JsonArray list = read("whitelist.json");
				if (list.asList().stream().noneMatch(e -> same(e, target))) list.add(entry(target));
				write("whitelist.json", list);
			}
			case "whitelist-remove" -> {
				JsonArray list = read("whitelist.json");
				list.asList().removeIf(e -> same(e, target));
				write("whitelist.json", list);
			}
			case "op" -> {
				JsonArray list = read("ops.json");
				list.asList().removeIf(e -> same(e, target));
				JsonObject o = entry(target);
				o.addProperty("level", Math.max(1, Math.min(4, level)));
				o.addProperty("bypassesPlayerLimit", false);
				list.add(o);
				write("ops.json", list);
			}
			case "deop" -> {
				JsonArray list = read("ops.json");
				list.asList().removeIf(e -> same(e, target));
				write("ops.json", list);
			}
			case "ban" -> {
				JsonArray list = read("banned-players.json");
				list.asList().removeIf(e -> same(e, target));
				JsonObject o = entry(target);
				o.addProperty("created", created);
				o.addProperty("source", "Panel (" + by + ")");
				o.addProperty("expires", "forever");
				o.addProperty("reason", reason.isEmpty() ? "Banned by an operator." : reason);
				list.add(o);
				write("banned-players.json", list);
			}
			case "pardon" -> {
				JsonArray list = read("banned-players.json");
				list.asList().removeIf(e -> same(e, target));
				write("banned-players.json", list);
			}
			case "ban-ip" -> {
				JsonArray list = read("banned-ips.json");
				String t = target;
				list.asList().removeIf(e -> e.getAsJsonObject().has("ip") && e.getAsJsonObject().get("ip").getAsString().equals(t));
				JsonObject o = new JsonObject();
				o.addProperty("ip", target);
				o.addProperty("created", created);
				o.addProperty("source", "Panel (" + by + ")");
				o.addProperty("expires", "forever");
				o.addProperty("reason", reason.isEmpty() ? "Banned by an operator." : reason);
				list.add(o);
				write("banned-ips.json", list);
			}
			case "pardon-ip" -> {
				JsonArray list = read("banned-ips.json");
				String t = target;
				list.asList().removeIf(e -> e.getAsJsonObject().has("ip") && e.getAsJsonObject().get("ip").getAsString().equals(t));
				write("banned-ips.json", list);
			}
			case "whitelist-on", "whitelist-off" -> setProperty("white-list", String.valueOf(action.equals("whitelist-on")));
			default -> {
				return "Unknown action.";
			}
		}
		audit.log(by, "players." + action, note, null);
		return null;
	}

	/** Changes one line of server.properties, keeping the rest (and its comments) as it is. */
	private void setProperty(String key, String value) throws IOException {
		Path f = dir.resolve("server.properties");
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

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/players/lists", guard(Permissions.PLAYERS_MANAGE, (ctx, me) -> json(ctx, view())));
		routes.post("/api/players/lists", guard(Permissions.PLAYERS_MANAGE, (ctx, me) -> {
			JsonObject b = body(ctx);
			int level = 4;
			try {
				level = Integer.parseInt(str(b, "level").isBlank() ? "4" : str(b, "level"));
			} catch (NumberFormatException e) {
				// 4
			}
			try {
				String error = apply(str(b, "action"), str(b, "target").strip(), str(b, "reason"), level, me.name());
				if (error != null) {
					error(ctx, HttpStatus.BAD_REQUEST, error);
					return;
				}
				json(ctx, Map.of("ok", true, "console", server.running()));
			} catch (IOException e) {
				error(ctx, HttpStatus.BAD_REQUEST, e.getMessage());
			}
		}));
	}
}
