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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gameplay: gameplay tweaks in config/nicecontrolcenter-tweaks.json, applied by the mod within seconds. */
final class TweaksRoutes {
	private final Path serverDir;
	private final Supervisor server;
	private final Audit audit;
	private final MapService map;

	TweaksRoutes(Path serverDir, Supervisor server, Audit audit, MapService map) {
		this.serverDir = serverDir;
		this.server = server;
		this.audit = audit;
		this.map = map;
	}

	Path file() {
		return serverDir.resolve("config").resolve(Tweaks.FILE);
	}

	void register(RoutesConfig routes) {
		routes.get("/api/tweaks", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Tweaks.Settings s = Tweaks.read(file());
			// Every dimension of the world, plus ones only in the settings, for the tab list names.
			List<Map<String, Object>> dims = new ArrayList<>();
			for (MapService.Dim d : map.dimensions()) dims.add(Map.of("id", d.id(), "name", d.name()));
			for (String id : s.tabList.dimensions.keySet()) {
				if (dims.stream().noneMatch(d -> d.get("id").equals(id))) dims.add(Map.of("id", id, "name", id));
			}
			// Performance → quick despawn, so the saplings card can warn when saplings vanish before they plant.
			var despawn = eu.explorerseden.nicecontrolcenter.Performance.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Performance.FILE)).despawn;
			Map<String, Object> quick = Map.of("enabled", despawn.enabled, "seconds", despawn.itemSeconds, "items", despawn.items);
			json(ctx, Map.of("settings", s, "defaults", new Tweaks.Settings().fill(), "dimensions", dims, "quickDespawn", quick));
		}));
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
