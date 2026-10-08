package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Fabric and Minecraft versions. */
final class VersionRoutes {
	private final Versions versions;

	VersionRoutes(Versions versions) {
		this.versions = versions;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/versions", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("installed", versions.installed());
			out.put("status", versions.status());
			json(ctx, out);
		}));

		routes.get("/api/versions/available", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			try {
				json(ctx, versions.available(ctx.queryParam("mc")));
			} catch (IOException e) {
				error(ctx, HttpStatus.BAD_GATEWAY, "Fabric's version list isn't reachable: " + e.getMessage());
			}
		}));

		routes.post("/api/versions/check", guard(Permissions.VERSIONS_MANAGE, (ctx, me) -> {
			JsonObject body = body(ctx);
			String mc = str(body, "mc");
			String loader = str(body, "loader");
			if (mc.isBlank()) {
				error(ctx, HttpStatus.BAD_REQUEST, "Choose a Minecraft version.");
				return;
			}
			try {
				json(ctx, Map.of("mods", versions.check(mc, loader.isBlank() ? null : loader)));
			} catch (IOException e) {
				error(ctx, HttpStatus.BAD_GATEWAY, "Modrinth isn't reachable: " + e.getMessage());
			}
		}));

		routes.post("/api/versions/update", guard(Permissions.VERSIONS_MANAGE, (ctx, me) -> {
			JsonObject body = body(ctx);
			Map<String, String> actions = new LinkedHashMap<>();
			if (body.has("actions") && body.get("actions").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : body.getAsJsonObject("actions").entrySet()) actions.put(e.getKey(), e.getValue().getAsString());
			}
			result(ctx, versions.start(str(body, "mc"), str(body, "loader"), actions, me.name()));
		}));

		routes.post("/api/versions/undo", guard(Permissions.VERSIONS_MANAGE, (ctx, me) -> result(ctx, versions.undo(str(body(ctx), "backup"), me.name()))));
	}
}
