package eu.explorerseden.nicecontrolcenter.panel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;

/**
 * Several people with the panel open at once: every settings address (one that's loaded with GET and saved with POST
 * under the same path) has a revision. Loading tells the page which revision it got; saving sends it back, and a save
 * made on top of an older revision than the current one is refused (someone else saved meanwhile), so nobody
 * overwrites another's changes by accident. Every save is also announced over a WebSocket, so open pages without
 * unsaved changes load the new state at once.
 */
final class Live {
	/** Changes with each panel start: revisions from before a restart are unknown, not "old". */
	private static final String BOOT = UUID.randomUUID().toString().substring(0, 8);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private record Rev(long n, String by, long at) {
	}

	private final Map<String, Rev> revs = new ConcurrentHashMap<>();
	/** Paths that answered a GET: only those are settings that pages load and save. */
	private final Set<String> loadable = ConcurrentHashMap.newKeySet();
	private final Set<WsContext> sockets = ConcurrentHashMap.newKeySet();

	private static final Set<String> SAVE_TAILS = Set.of("settings", "edit", "motd", "motds");

	/**
	 * The settings a POST saves: its own path when that's loaded too, else the parent's for "…/settings", "…/edit",
	 * "…/motd(s)" (the Discord page loads /api/discord and saves /api/discord/settings). Null: not a settings save.
	 */
	String key(String path) {
		if (loadable.contains(path)) return path;
		int cut = path.lastIndexOf('/');
		if (cut <= 0) return null;
		String parent = path.substring(0, cut);
		return SAVE_TAILS.contains(path.substring(cut + 1)) && loadable.contains(parent) ? parent : null;
	}

	private Rev rev(String path) {
		return revs.getOrDefault(path, new Rev(0, null, 0));
	}

	/**
	 * Before a POST: refused (true) when the page sends the revision it loaded and someone else has saved since.
	 */
	boolean conflict(Context ctx) {
		if (!ctx.method().name().equals("POST")) return false;
		String sent = ctx.header("X-Rev");
		if (sent == null || !sent.startsWith(BOOT + ":")) return false;
		long n;
		try {
			n = Long.parseLong(sent.substring(BOOT.length() + 1));
		} catch (NumberFormatException e) {
			return false;
		}
		String key = key(ctx.path());
		if (key == null) return false;
		Rev now = rev(key);
		if (n >= now.n) return false;
		String who = now.by == null ? "Someone" : now.by;
		Web.error(ctx, io.javalin.http.HttpStatus.CONFLICT, who + " saved this at " + TIME.format(Instant.ofEpochMilli(now.at))
				+ " while you were editing, and saving now would overwrite that. Cancel loads their version; then make your change again.");
		return true;
	}

	/** After a request: GETs get the current revision, successful POSTs to settings raise it and are announced. */
	void after(Context ctx, String user) {
		String path = ctx.path();
		int status = ctx.statusCode();
		if (status < 200 || status >= 300) return;
		if (ctx.method().name().equals("GET")) {
			loadable.add(path);
			ctx.header("X-Rev", BOOT + ":" + rev(path).n);
			return;
		}
		if (!ctx.method().name().equals("POST")) return;
		// A save of the settings, or any other change under a loaded address (a dungeon saved again…): announced.
		String key = key(path);
		if (key == null) {
			int cut = path.lastIndexOf('/');
			String parent = cut > 0 ? path.substring(0, cut) : null;
			if (parent == null || !loadable.contains(parent)) return;
			key = parent;
		}
		path = key;
		Rev next = revs.compute(path, (k, old) -> new Rev((old == null ? 0 : old.n) + 1, user, System.currentTimeMillis()));
		ctx.header("X-Rev", BOOT + ":" + next.n);
		String tab = ctx.header("X-Tab");
		String msg = Json.GSON.toJson(Map.of("path", path.substring(1), "by", user == null ? "Someone" : user, "tab", tab == null ? "" : tab));
		for (WsContext ws : sockets) {
			try {
				synchronized (ws) {
					ws.send(msg);
				}
			} catch (RuntimeException e) {
				sockets.remove(ws);
			}
		}
	}

	void join(WsContext ws) {
		ws.enableAutomaticPings();
		sockets.add(ws);
	}

	void leave(WsContext ws) {
		sockets.remove(ws);
	}
}
