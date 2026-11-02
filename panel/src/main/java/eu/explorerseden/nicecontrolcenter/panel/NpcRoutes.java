package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Npcs;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Points;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Community → NPCs: the NPC file (the mod picks changes up within five seconds). */
final class NpcRoutes {
	private final Path serverDir;
	private final Audit audit;
	private final ItemIcons icons;

	NpcRoutes(Path serverDir, Audit audit, ItemIcons icons) {
		this.serverDir = serverDir;
		this.audit = audit;
		this.icons = icons;
	}

	private Path file() {
		return serverDir.resolve("config").resolve(Npcs.FILE);
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/npcs", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Npcs.Settings s = Npcs.read(file());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("settings", s);
			Map<String, String> skins = new LinkedHashMap<>();
			Map<String, Object> gear = new LinkedHashMap<>();
			for (Npcs.Npc n : s.npcs) {
				String hash = Pets.skinHash(Pets.texture(n.skin));
				if (hash != null) skins.put(n.id, hash);
				if (!n.equipment.isEmpty()) gear.put(n.id, ClaimService.kitItems(n.equipment, icons));
			}
			out.put("skins", skins);
			out.put("equipment", gear);
			List<Map<String, String>> shops = new ArrayList<>();
			for (Points.Shop shop : Points.read(serverDir.resolve("config").resolve(Points.FILE)).shops) shops.add(Map.of("id", shop.id, "name", shop.name, "title", shop.shown()));
			out.put("shops", shops);
			out.put("poses", Npcs.POSES);
			json(ctx, out);
		}));
		routes.post("/api/npcs", guard(Permissions.SETTINGS_SERVER, (ctx, me) -> {
			Npcs.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Npcs.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			String problem = Npcs.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			// Set in game (spots, paths, equipment, adopted or made NPCs), also while the page was open: kept as the mod
			// wrote them; only what the page deleted goes.
			Npcs.Settings now = Npcs.read(file());
			Removed removed = Removed.from(ctx.body());
			Set<String> goneNpcs = removed.of("npcs"), gonePath = removed.of("path");
			for (Npcs.Npc n : next.npcs) {
				Npcs.Npc was = now.npcs.stream().filter(x -> x.id.equals(n.id)).findFirst().orElse(null);
				if (was == null) {
					n.spot = null;
					n.equipment = new ArrayList<>();
					n.path = new ArrayList<>();
					continue;
				}
				n.spot = was.spot;
				n.equipment = was.equipment;
				List<Npcs.Checkpoint> path = new ArrayList<>();
				for (Npcs.Checkpoint c : was.path) {
					if (gonePath.contains(Removed.spot(n.id, "path", c.pos))) continue;
					// The wait may be changed on the page.
					Npcs.Checkpoint mine = n.path.stream().filter(x -> x.pos != null && Removed.spot(n.id, "path", x.pos).equals(Removed.spot(n.id, "path", c.pos))).findFirst().orElse(c);
					c.waitSeconds = mine.waitSeconds;
					path.add(c);
				}
				n.path = path;
			}
			for (Npcs.Npc was : now.npcs) {
				if (!goneNpcs.contains(was.id) && next.npcs.stream().noneMatch(x -> x.id.equals(was.id))) next.npcs.add(was);
			}
			next.shopsMigrated = now.shopsMigrated;
			problem = Npcs.validate(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			Npcs.write(file(), next);
			audit.log(me.name(), "npcs.settings", next.npcs.size() + " NPCs", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}
}
