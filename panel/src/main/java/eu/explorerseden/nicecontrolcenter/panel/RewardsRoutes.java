package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Rewards;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Path;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Event rewards (config/nicecontrolcenter-rewards.json): the mod picks up changes within a few seconds. */
final class RewardsRoutes {
	private final Path serverDir;
	private final Audit audit;

	RewardsRoutes(Path serverDir, Audit audit) {
		this.serverDir = serverDir;
		this.audit = audit;
	}

	private Path file() {
		return serverDir.resolve("config").resolve(Rewards.FILE);
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/rewards", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			// The pets admins can give (by name), for the "Also give" picker.
			var pets = eu.explorerseden.nicecontrolcenter.Pets.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Pets.FILE)).pets.stream().map(p -> p.name).toList();
			// The points' own name, for the "Points" reward's label.
			var points = eu.explorerseden.nicecontrolcenter.Points.read(serverDir.resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Points.FILE));
			json(ctx, Map.of("settings", Rewards.read(file()), "triggers", Rewards.TRIGGERS, "actions", Rewards.ACTIONS, "pets", pets,
					"pointsName", points.name, "pointsShort", points.abbreviation));
		}));
		routes.post("/api/rewards", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Rewards.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Rewards.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Rewards.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Rewards.write(file(), next);
			audit.log(me.name(), "rewards.settings", next.rules.size() + " rules" + (next.enabled ? "" : ", all off"), clientIp(ctx));
			json(ctx, Map.of("ok", true, "settings", next));
		}));
	}
}
