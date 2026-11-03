package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.Slots;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Games → Slot machines: the types (cost, wins) and the machines placed in game (the mod picks changes up within five seconds). */
final class SlotRoutes {
	private final Path serverDir;
	private final Audit audit;
	private final ItemIcons icons;

	SlotRoutes(Path serverDir, Audit audit, ItemIcons icons) {
		this.serverDir = serverDir;
		this.audit = audit;
		this.icons = icons;
	}

	private Path file() {
		return serverDir.resolve("config").resolve(Slots.FILE);
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/slots", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Slots.Settings s = Slots.read(file());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", s);
			out.put("looks", Slots.LOOKS);
			Points.Settings ps = Points.read(serverDir.resolve("config").resolve(Points.FILE));
			out.put("points", Map.of("name", ps.name, "enabled", ps.enabled));
			// Item pictures for the symbols in use.
			Map<String, String> pics = new LinkedHashMap<>();
			for (Slots.Type t : s.types) for (String id : t.symbols()) if (!pics.containsKey(id)) {
				String hash = icons.iconFor("{\"id\":\"" + id + "\"}");
				if (hash != null) pics.put(id, hash);
			}
			out.put("icons", pics);
			json(ctx, out);
		}));
		routes.post("/api/slots", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Slots.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Slots.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Slots.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			// Machines are placed in game (also while the page was open): kept as the mod wrote them; the page only
			// changes their type, or forgets the ones it removed. Types made elsewhere since stay too.
			Slots.Settings now = Slots.read(file());
			Removed removed = Removed.from(ctx.body());
			Set<String> goneMachines = removed.of("machines"), goneTypes = removed.of("types");
			for (Slots.Machine was : now.machines) {
				if (goneMachines.contains(was.id)) continue;
				Slots.Machine mine = next.machines.stream().filter(x -> x.id.equals(was.id)).findFirst().orElse(null);
				if (mine != null) was.type = mine.type;
			}
			now.machines.removeIf(m -> goneMachines.contains(m.id));
			next.machines = now.machines;
			for (Slots.Type was : now.types) {
				if (!goneTypes.contains(was.id) && next.types.stream().noneMatch(x -> x.id.equals(was.id))) next.types.add(was);
			}
			problem = Slots.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Slots.write(file(), next);
			audit.log(me.name(), "slots.settings", next.types.size() + " types, " + next.machines.size() + " machines", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
