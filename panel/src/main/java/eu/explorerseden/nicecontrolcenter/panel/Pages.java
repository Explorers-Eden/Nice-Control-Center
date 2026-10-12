package eu.explorerseden.nicecontrolcenter.panel;

import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The HTML pages (panel, editor, map, info page) with the panel's version on their own scripts and
 * styles ("panel.js?v=1.7.4"), so browsers and proxies load the new files after an update instead
 * of a cached old copy. The pages themselves are never cached.
 */
final class Pages {
	private static final Map<String, String> PAGES = Map.of("/", "index.html", "/index.html", "index.html", "/editor.html", "editor.html",
			"/map/", "map/index.html", "/map/index.html", "map/index.html", "/info/", "info/index.html", "/info/index.html", "info/index.html");
	/** Local .css/.js files (not https://…). */
	private static final Pattern ASSET = Pattern.compile("((?:href|src)=\")((?:\\.\\./)?[A-Za-z0-9_-]+\\.(?:css|js))\"");
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private Pages() {
	}

	static void register(JavalinDefaultRoutingApi routes) {
		routes.before(ctx -> {
			if (!ctx.method().name().equals("GET")) return;
			String file = PAGES.get(ctx.path());
			if (file == null) return;
			String html = CACHE.computeIfAbsent(file, Pages::load);
			if (html.isEmpty()) return;
			ctx.contentType("text/html; charset=utf-8").header("Cache-Control", "no-cache").result(html);
			ctx.skipRemainingHandlers();
		});
	}

	private static String load(String file) {
		try (InputStream in = Pages.class.getResourceAsStream("/panel/web/" + file)) {
			if (in == null) return "";
			String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return ASSET.matcher(html).replaceAll("$1$2?v=" + Panel.VERSION + "\"");
		} catch (IOException e) {
			return "";
		}
	}
}
