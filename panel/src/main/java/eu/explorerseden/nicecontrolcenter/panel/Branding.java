package eu.explorerseden.nicecontrolcenter.panel;

import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Logos: one for the panel (replaces the "Nice Control Center" text in the navbar, the icon stays) and one
 * for the public map, plus the background picture of the public info page's banner. Kept in
 * /data/panel/branding. Only PNG, JPEG, WebP and GIF, checked by their content, up to 2 MB (the banner
 * picture 8 MB; no SVG: it can carry scripts).
 */
final class Branding {
	private static final long MAX = 2L * 1024 * 1024;
	private static final long MAX_BANNER = 8L * 1024 * 1024;

	private final Path dir;
	private final Audit audit;

	Branding(Path dataDir, Audit audit) {
		this.dir = dataDir.resolve("branding");
		this.audit = audit;
	}

	private Path file(String which) {
		return dir.resolve(which + ".img");
	}

	boolean has(String which) {
		return Files.isRegularFile(file(which));
	}

	/** The image type from the first bytes, or null when it isn't one we take. */
	private static String type(byte[] head) {
		if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "image/png";
		if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "image/jpeg";
		if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "image/webp";
		if (head.length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return "image/gif";
		return null;
	}

	private void serve(io.javalin.http.Context ctx, String which) throws IOException {
		Path f = file(which);
		if (!Files.isRegularFile(f)) {
			ctx.status(HttpStatus.NOT_FOUND).result("No logo");
			return;
		}
		// The browser keeps the logo and checks back with its ETag, so pages don't flash the text first.
		String etag = "\"" + Files.getLastModifiedTime(f).toMillis() + "-" + Files.size(f) + "\"";
		if (etag.equals(ctx.header("If-None-Match"))) {
			ctx.status(HttpStatus.NOT_MODIFIED).header("ETag", etag);
			return;
		}
		byte[] bytes = Files.readAllBytes(f);
		String type = type(bytes);
		if (type == null) {
			ctx.status(HttpStatus.NOT_FOUND).result("No logo");
			return;
		}
		ctx.contentType(type).header("Cache-Control", "no-cache").header("ETag", etag).header("X-Content-Type-Options", "nosniff").result(bytes);
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Public: the login screen and the public map show them.
		routes.get("/branding/panel", ctx -> serve(ctx, "panel"));
		routes.get("/map/data/logo", ctx -> serve(ctx, "map"));
		routes.get("/info/banner", ctx -> serve(ctx, "infobanner"));

		for (String which : new String[] { "panel", "map", "infobanner" }) {
			String perm = switch (which) {
				case "map" -> Permissions.MAP_MANAGE;
				case "infobanner" -> Permissions.SETTINGS_SERVER;
				default -> Permissions.USERS_ADMIN;
			};
			long max = which.equals("infobanner") ? MAX_BANNER : MAX;
			String what = which.equals("infobanner") ? "picture" : "logo";
			routes.post("/api/branding/" + which, guard(perm, (ctx, me) -> {
				UploadedFile upload = ctx.uploadedFile("logo");
				if (upload == null) {
					error(ctx, HttpStatus.BAD_REQUEST, "Choose an image.");
					return;
				}
				if (upload.size() > max) {
					error(ctx, HttpStatus.BAD_REQUEST, "The " + what + " can be up to " + max / 1024 / 1024 + " MB.");
					return;
				}
				byte[] bytes;
				try (InputStream in = upload.content()) {
					bytes = in.readNBytes((int) max + 1);
				}
				if (type(bytes) == null) {
					error(ctx, HttpStatus.BAD_REQUEST, "Use a PNG, JPEG, WebP or GIF image.");
					return;
				}
				Files.createDirectories(dir);
				Path tmp = dir.resolve(which + ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file(which), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				audit.log(me.name(), "branding." + which, "new " + what + " (" + bytes.length + " bytes)", clientIp(ctx));
				json(ctx, Map.of("ok", true));
			}));
			routes.post("/api/branding/" + which + "/remove", guard(perm, (ctx, me) -> {
				Files.deleteIfExists(file(which));
				audit.log(me.name(), "branding." + which, what + " removed", clientIp(ctx));
				json(ctx, Map.of("ok", true));
			}));
		}
	}
}
