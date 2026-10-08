package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Discord settings and links for admins, and the two internal routes the mod calls. */
final class DiscordRoutes {
	private final Discord discord;
	private final String secret;
	private final Audit audit;

	DiscordRoutes(Discord discord, String secret, Audit audit) {
		this.discord = discord;
		this.secret = secret;
		this.audit = audit;
	}

	/** Only the Minecraft server in this container, with the secret the panel gave it. */
	private boolean fromMod(Context ctx) {
		String given = ctx.header("X-NCC-Secret");
		boolean secretOk = given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
		boolean local;
		try {
			local = InetAddress.getByName(ctx.ip().replace("[", "").replace("]", "")).isLoopbackAddress();
		} catch (Exception e) {
			local = false;
		}
		if (!secretOk || !local) {
			error(ctx, HttpStatus.FORBIDDEN, "Not for you");
			return false;
		}
		return true;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.post("/internal/login", ctx -> {
			if (!fromMod(ctx)) return;
			JsonObject body = body(ctx);
			Discord.LoginAnswer answer = discord.login(str(body, "uuid"), str(body, "name"), Boolean.TRUE.equals(bool(body, "op")));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("allowed", answer.allowed());
			if (answer.message() != null) out.put("message", answer.message());
			json(ctx, out);
		});

		routes.post("/internal/event", ctx -> {
			if (!fromMod(ctx)) return;
			discord.event(body(ctx));
			json(ctx, Map.of("ok", true));
		});

		routes.get("/api/discord", guard(Permissions.DISCORD_MANAGE, (ctx, me) -> {
			Discord.Settings s = discord.settings();
			JsonObject settings = Json.GSON.toJsonTree(s).getAsJsonObject();
			settings.addProperty("token", s.token == null || s.token.isBlank() ? "" : "********");
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", settings);
			out.put("tokenFromEnv", discord.tokenFromEnv());
			out.put("status", discord.status());
			out.put("links", discord.links());
			json(ctx, out);
		}));

		routes.post("/api/discord/settings", guard(Permissions.DISCORD_MANAGE, (ctx, me) -> {
			Discord.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Discord.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unreadable settings");
				return;
			}
			String problem = discord.save(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			audit.log(me.name(), "discord.settings", (next.enabled ? "on" : "off") + (next.requireLink ? ", linking required" : "")
					+ (next.requireMember ? ", must be on the Discord server" : ""), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/discord/links/{uuid}/delete", guard(Permissions.DISCORD_MANAGE, (ctx, me) -> {
			String uuid = ctx.pathParam("uuid");
			String name = discord.links().stream().filter(l -> l.uuid().equals(uuid)).map(Discord.Link::name).findFirst().orElse(uuid);
			if (discord.unlink(uuid)) audit.log(me.name(), "discord.unlink", name, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
