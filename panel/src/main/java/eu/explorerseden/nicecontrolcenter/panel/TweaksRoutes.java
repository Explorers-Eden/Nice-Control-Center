package eu.explorerseden.nicecontrolcenter.panel;

import static eu.explorerseden.nicecontrolcenter.panel.Web.clientIp;
import static eu.explorerseden.nicecontrolcenter.panel.Web.error;
import static eu.explorerseden.nicecontrolcenter.panel.Web.guard;
import static eu.explorerseden.nicecontrolcenter.panel.Web.json;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Tweaks;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpStatus;

import java.nio.file.Path;
import java.util.Map;

/** Settings → Tweaks: gameplay tweaks in config/nicecontrolcenter-tweaks.json, applied by the mod within seconds. */
final class TweaksRoutes {
	private final Path serverDir;
	private final Supervisor server;
	private final Audit audit;

	TweaksRoutes(Path serverDir, Supervisor server, Audit audit) {
		this.serverDir = serverDir;
		this.server = server;
		this.audit = audit;
	}

	Path file() {
		return serverDir.resolve("config").resolve(Tweaks.FILE);
	}

	void register(RoutesConfig routes) {
		routes.get("/api/tweaks", guard(Permissions.SERVER_VIEW, (ctx, me) ->
				json(ctx, Map.of("settings", Tweaks.read(file()), "defaults", new Tweaks.Settings().fill()))));
		routes.post("/api/tweaks", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Tweaks.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Tweaks.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Tweaks.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Tweaks.write(file(), next);
			audit.log(me.name(), "tweaks.settings", "saved", clientIp(ctx));
			json(ctx, Map.of("ok", true, "running", server.running()));
		}));
	}
}
