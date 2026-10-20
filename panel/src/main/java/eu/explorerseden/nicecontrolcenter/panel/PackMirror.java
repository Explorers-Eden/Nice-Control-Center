package eu.explorerseden.nicecontrolcenter.panel;

import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hands out the server resource pack from this host instead of GitHub (some players can't reach GitHub's
 * download servers reliably, but they can reach the game here). The mod keeps a copy of each pack it finds
 * as nicecontrolcenter/resourcepack/<sha1>.zip and puts this address with the SHA-1 into server.properties;
 * the link changes with the pack, so a link and its SHA-1 always match.
 */
final class PackMirror {
	/** Where the mod keeps the copies, in the server folder. */
	static final String FOLDER = "nicecontrolcenter/resourcepack";

	private final Path dir;

	PackMirror(Path serverDir) {
		this.dir = serverDir.resolve(FOLDER);
	}

	/** The public address the mod links to (without the file name), or empty when the panel has none. */
	static String publicBase() {
		String info = Panel.env("INFO_HOST", "").strip().toLowerCase();
		if (!info.isEmpty()) return "https://" + info + "/info/pack";
		String panel = Panel.env("PANEL_PUBLIC_URL", "").strip().replaceFirst("/+$", "");
		return panel.isEmpty() ? "" : panel + "/info/pack";
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Public like the info page (also when that's switched off): players' games fetch it without a login.
		routes.get("/info/pack/{file}", ctx -> {
			String name = ctx.pathParam("file");
			Path f = name.matches("[0-9a-f]{40}\\.zip") ? dir.resolve(name) : null;
			if (f == null || !Files.isRegularFile(f)) {
				ctx.status(HttpStatus.NOT_FOUND).result("Not found");
				return;
			}
			try {
				long size = Files.size(f);
				// Sent as it is (compressing would write fewer bytes than the length says). The name is the
				// content's SHA-1, so it never changes.
				ctx.disableCompression();
				ctx.contentType("application/zip").header("Cache-Control", "public, max-age=31536000, immutable")
						.header("Content-Length", Long.toString(size)).result(Files.newInputStream(f));
			} catch (IOException e) {
				// Removed meanwhile (a newer pack replaced it).
				ctx.status(HttpStatus.NOT_FOUND).result("Not found");
			}
		});
	}
}
