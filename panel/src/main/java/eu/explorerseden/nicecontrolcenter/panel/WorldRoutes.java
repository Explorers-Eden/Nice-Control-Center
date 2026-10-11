package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Borders;
import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Borders and the world trimmer (pregeneration runs in the mod and goes through the dashboard proxy).
 * Trims always follow the saved borders: everything outside a dimension's soft border goes.
 */
final class WorldRoutes {
	private final Trimmer trimmer;
	private final Jobs jobs;

	WorldRoutes(Trimmer trimmer, Jobs jobs) {
		this.trimmer = trimmer;
		this.jobs = jobs;
	}

	private static int keepMinutes(JsonObject b) {
		try {
			return Math.max(0, Integer.parseInt(str(b, "keepVisitedMinutes").isBlank() ? "0" : str(b, "keepVisitedMinutes")));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The outer wilds of every dimension with borders, or of the one asked for. */
	private List<Trimmer.Area> areas(JsonObject b) {
		int keep = keepMinutes(b);
		String only = str(b, "dimension");
		List<Trimmer.Area> out = new ArrayList<>();
		for (Trimmer.Area a : trimmer.wildsAreas()) {
			if (!only.isBlank() && !only.equals(a.dimension())) continue;
			out.add(new Trimmer.Area(a.dimension(), a.x(), a.z(), a.radius(), a.circle(), keep));
		}
		return out;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/trim", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>(trimmer.status());
			out.put("dimensions", trimmer.dimensions());
			out.put("borders", trimmer.borders());
			json(ctx, out);
		}));

		routes.post("/api/borders/save", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			JsonObject b = body(ctx);
			Borders.Border border = b.has("border") && b.get("border").isJsonObject() ? Json.GSON.fromJson(b.get("border"), Borders.Border.class) : null;
			String error = trimmer.saveBorder(str(b, "dimension"), border, me.name());
			if (error != null) {
				error(ctx, HttpStatus.BAD_REQUEST, error);
				return;
			}
			json(ctx, Map.of("ok", true, "borders", trimmer.borders()));
		}));

		routes.post("/api/trim/preview", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			List<Trimmer.Area> areas = areas(body(ctx));
			if (areas.isEmpty()) {
				error(ctx, HttpStatus.BAD_REQUEST, "Save borders for this dimension first.");
				return;
			}
			// Reading every region file (and with "keep visited", every chunk outside) can take minutes.
			Trimmer.Area area = areas.get(0);
			Jobs.Job job = jobs.submit("trim-preview", "Trim preview of " + area.dimension(), me.name(), () -> {
				Trimmer.Plan p = trimmer.plan(area);
				trimmer.preview(p);
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("regions", p.regions().size());
				out.put("partialRegions", p.chunks().size());
				out.put("chunks", p.chunkCount());
				out.put("bytes", p.bytes());
				out.put("regionsKept", p.regionsKept());
				return out;
			});
			json(ctx, Map.of("ok", true, "job", job.id));
		}));

		routes.post("/api/trim/clear", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			trimmer.clearPreview();
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/trim/run", guard(Permissions.WORLD_TRIM, (ctx, me) -> {
			List<Trimmer.Area> areas = areas(body(ctx));
			result(ctx, areas.isEmpty() ? "No borders are set, so there are no outer wilds to trim." : trimmer.start(areas, me.name()));
		}));
	}
}
