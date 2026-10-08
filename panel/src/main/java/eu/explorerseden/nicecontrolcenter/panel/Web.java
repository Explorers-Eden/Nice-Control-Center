package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small helpers shared by the route classes. */
final class Web {
	static final String SESSION = "session";

	interface Action {
		void handle(Context ctx, Auth.Session session) throws Exception;
	}

	private Web() {
	}

	/** Runs the action only when the logged-in user has the permission (null: any logged-in user). */
	static Handler guard(String permission, Action action) {
		return ctx -> {
			Auth.Session session = ctx.attribute(SESSION);
			if (session == null) {
				error(ctx, HttpStatus.UNAUTHORIZED, "Please log in");
				return;
			}
			if (permission != null && !session.can(permission)) {
				error(ctx, HttpStatus.FORBIDDEN, "Your account isn't allowed to do that.");
				return;
			}
			action.handle(ctx, session);
		};
	}

	static void json(Context ctx, Object value) {
		ctx.contentType("application/json").header("Cache-Control", "no-store").result(Json.GSON.toJson(value));
	}

	static void error(Context ctx, HttpStatus status, String message) {
		json(ctx.status(status), Map.of("error", message));
	}

	static void result(Context ctx, String error) {
		if (error == null) json(ctx, Map.of("ok", true));
		else error(ctx, HttpStatus.CONFLICT, error);
	}

	static JsonObject body(Context ctx) {
		try {
			return JsonParser.parseString(ctx.body()).getAsJsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	static String str(JsonObject body, String key) {
		return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString() : "";
	}

	/** Null when the key is missing, so updates can leave a field unchanged. */
	static Boolean bool(JsonObject body, String key) {
		return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsBoolean() : null;
	}

	static List<String> strings(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonArray()) return null;
		List<String> out = new ArrayList<>();
		JsonArray array = body.getAsJsonArray(key);
		for (JsonElement e : array) if (e.isJsonPrimitive()) out.add(e.getAsString());
		return out;
	}

	static List<Integer> ints(JsonObject body, String key) {
		List<String> raw = strings(body, key);
		if (raw == null) return null;
		List<Integer> out = new ArrayList<>();
		for (String s : raw) {
			try {
				out.add(Integer.parseInt(s));
			} catch (NumberFormatException e) {
				// Ignored.
			}
		}
		return out;
	}

	/**
	 * The client's address for the login limit and the audit log. Behind a reverse proxy it's in
	 * X-Forwarded-For, but only the proxy's own entries can be trusted: a client can send the header
	 * itself, and proxies append to it. So X-Forwarded-For only counts when the request comes from a
	 * private address (the proxy), and then its right-most public entry is the client.
	 */
	static String clientIp(Context ctx) {
		String peer = clean(ctx.ip());
		String forwarded = ctx.header("X-Forwarded-For");
		if (forwarded == null || forwarded.isBlank() || !privateAddress(peer)) return peer;
		String[] hops = forwarded.split(",");
		String ip = peer;
		for (int i = hops.length - 1; i >= 0; i--) {
			ip = clean(hops[i].strip());
			if (!privateAddress(ip)) break;
		}
		return ip;
	}

	private static String clean(String ip) {
		// Jetty writes IPv6 addresses in brackets and long form.
		ip = ip.replace("[", "").replace("]", "");
		return ip.equals("0:0:0:0:0:0:0:1") ? "::1" : ip;
	}

	private static boolean privateAddress(String ip) {
		// Only literal addresses; anything else (a name, garbage) counts as public and is used as it is.
		if (!ip.matches("[0-9a-fA-F:.]+")) return false;
		try {
			java.net.InetAddress a = java.net.InetAddress.ofLiteral(ip);
			return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
					|| (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
