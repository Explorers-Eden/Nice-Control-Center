package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Per account: a profile picture (square-cropped to 128 × 128 PNG) and the chosen theme. Kept in
 * /data/panel/profiles by account name, so they follow the account to every browser. Without a picture,
 * an account named like a player who has joined the server gets that player's face.
 */
final class Profiles {
	static final Set<String> THEMES = Set.of("dark", "light", "midnight", "forest", "nether", "end", "ocean", "sakura");

	private final Path dir;
	private final Path serverDir;
	private final Audit audit;

	Profiles(Path dataDir, Path serverDir, Audit audit) {
		this.dir = dataDir.resolve("profiles");
		this.serverDir = serverDir;
		this.audit = audit;
	}

	/** The UUID of the player with this name in usercache.json (any case), or null. */
	private String playerUuid(String name) {
		try {
			Path f = serverDir.resolve("usercache.json");
			if (!Files.isRegularFile(f)) return null;
			for (var e : JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				if (o.has("name") && o.has("uuid") && o.get("name").getAsString().equalsIgnoreCase(name)) return o.get("uuid").getAsString();
			}
		} catch (IOException | RuntimeException e) {
			// No face then.
		}
		return null;
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
	}

	private Path avatar(String name) {
		return dir.resolve(key(name) + ".png");
	}

	private Path prefs(String name) {
		return dir.resolve(key(name) + ".json");
	}

	boolean hasAvatar(String name) {
		return Files.isRegularFile(avatar(name));
	}

	String theme(String name) {
		try {
			Path f = prefs(name);
			if (Files.exists(f)) {
				JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				String t = o.has("theme") ? o.get("theme").getAsString() : null;
				if (THEMES.contains(t)) return t;
			}
		} catch (IOException | RuntimeException e) {
			// None chosen.
		}
		return null;
	}

	/** The upload, cropped to a centred square and scaled to 128 × 128. */
	private static byte[] picture(UploadedFile upload) throws IOException {
		if (upload == null) throw new IOException("Choose an image.");
		if (upload.size() > 8L * 1024 * 1024) throw new IOException("The picture can be up to 8 MB.");
		BufferedImage in;
		try (InputStream s = upload.content()) {
			in = ImageIO.read(new ByteArrayInputStream(s.readNBytes(8 * 1024 * 1024 + 1)));
		}
		if (in == null) throw new IOException("Use a PNG, JPEG or GIF image.");
		int side = Math.min(in.getWidth(), in.getHeight());
		BufferedImage square = in.getSubimage((in.getWidth() - side) / 2, (in.getHeight() - side) / 2, side, side);
		BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, side < 128 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(square, 0, 0, 128, 128, null);
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Any logged-in account may see pictures (users list, audit log); names only, no listing.
		routes.get("/api/avatar/{name}", guard(null, (ctx, me) -> {
			Path f = avatar(ctx.pathParam("name"));
			if (!Files.isRegularFile(f)) {
				String uuid = playerUuid(ctx.pathParam("name"));
				if (uuid == null) {
					ctx.status(HttpStatus.NOT_FOUND).result("No picture");
					return;
				}
				ctx.header("Cache-Control", "private, max-age=3600").redirect("https://mc-heads.net/avatar/" + uuid + "/128", HttpStatus.FOUND);
				return;
			}
			String etag = "\"" + Files.getLastModifiedTime(f).toMillis() + "\"";
			if (etag.equals(ctx.header("If-None-Match"))) {
				ctx.status(HttpStatus.NOT_MODIFIED);
				return;
			}
			ctx.contentType("image/png").header("Cache-Control", "private, no-cache").header("ETag", etag).result(Files.readAllBytes(f));
		}));
		routes.post("/api/me/avatar", guard(null, (ctx, me) -> {
			byte[] png;
			try {
				png = picture(ctx.uploadedFile("avatar"));
			} catch (IOException e) {
				error(ctx, HttpStatus.BAD_REQUEST, e.getMessage());
				return;
			}
			Files.createDirectories(dir);
			Path tmp = dir.resolve(key(me.name()) + ".tmp");
			Files.write(tmp, png);
			Files.move(tmp, avatar(me.name()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			audit.log(me.name(), "account.avatar", "new profile picture", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/me/avatar/remove", guard(null, (ctx, me) -> {
			Files.deleteIfExists(avatar(me.name()));
			json(ctx, Map.of("ok", true));
		}));
		routes.post("/api/me/theme", guard(null, (ctx, me) -> {
			String theme = str(body(ctx), "theme");
			if (!THEMES.contains(theme)) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unknown theme.");
				return;
			}
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("theme", theme);
			Files.writeString(prefs(me.name()), o.toString());
			json(ctx, Map.of("ok", true));
		}));
	}
}
