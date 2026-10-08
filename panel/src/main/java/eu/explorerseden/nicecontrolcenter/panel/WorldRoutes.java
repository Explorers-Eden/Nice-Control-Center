package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** World trimmer (pregeneration runs in the mod and goes through the dashboard proxy). */
final class WorldRoutes {
	private final Trimmer trimmer;

	WorldRoutes(Trimmer trimmer) {
		this.trimmer = trimmer;
	}

	private static Trimmer.Area area(Context ctx) {
		JsonObject b = body(ctx);
		int keep = 0;
		try {
			keep = Integer.parseInt(str(b, "keepVisitedMinutes").isBlank() ? "0" : str(b, "keepVisitedMinutes"));
		} catch (NumberFormatException e) {
			// 0
		}
		return new Trimmer.Area(str(b, "dimension"), (int) Double.parseDouble(str(b, "x")), (int) Double.parseDouble(str(b, "z")),
				(int) Double.parseDouble(str(b, "radius")), "circle".equals(str(b, "shape")), Math.max(0, keep));
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/trim", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>(trimmer.status());
			out.put("dimensions", trimmer.dimensions());
			json(ctx, out);
		}));

		routes.post("/api/trim/preview", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			try {
				Trimmer.Plan p = trimmer.plan(area(ctx));
				trimmer.preview(p);
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("regions", p.regions().size());
				out.put("partialRegions", p.chunks().size());
				out.put("chunks", p.chunkCount());
				out.put("bytes", p.bytes());
				out.put("regionsKept", p.regionsKept());
				json(ctx, out);
			} catch (IOException | NumberFormatException e) {
				error(ctx, HttpStatus.BAD_REQUEST, e instanceof NumberFormatException ? "Center and radius must be numbers." : e.getMessage());
			}
		}));

		routes.post("/api/trim/clear", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			trimmer.clearPreview();
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/trim/run", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			try {
				result(ctx, trimmer.start(area(ctx), me.name()));
			} catch (NumberFormatException e) {
				error(ctx, HttpStatus.BAD_REQUEST, "Center and radius must be numbers.");
			}
		}));
	}
}
