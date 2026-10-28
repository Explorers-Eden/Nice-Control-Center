package eu.explorerseden.nicecontrolcenter.claims;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.players.MapExport;
import eu.explorerseden.nicecontrolcenter.players.PlayerInspector;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the panel asks of claims through the dashboard API. All on the server thread. */
public final class ClaimApi {
	private ClaimApi() {
	}

	public static Map<String, Object> changes(long since) {
		ClaimStore.reloadSettings();
		Map<String, Object> out = ClaimStore.data().changes(since);
		out.put("enabled", ClaimStore.enabled());
		return out;
	}

	public static Map<String, Object> action(JsonObject body, String by) {
		ClaimStore.reloadSettings();
		// The kit from an online admin's inventory (needs the game, so not in Claims).
		if ("kit".equals(body.has("action") ? body.get("action").getAsString() : "")) {
			Claim c = ClaimStore.data().claims.get(body.has("claim") ? body.get("claim").getAsString() : "");
			if (c == null) return Map.of("error", "That claim doesn't exist any more.");
			var server = ClaimStore.server();
			String name = body.has("from") ? body.get("from").getAsString() : "";
			var p = server == null ? null : server.getPlayerList().getPlayerByName(name);
			if (p == null) return Map.of("error", "\"" + name + "\" isn't online: be in game with the items in your inventory.");
			int n = GameClaims.setKit(p, c);
			NiceControlCenter.LOGGER.info("Claims: kit of {} set from {}'s inventory ({} stacks) by {}", c.displayName(), name, n, by);
			Map<String, Object> out = new java.util.LinkedHashMap<>();
			out.put("ok", true);
			out.put("claim", c);
			out.put("kit", n);
			return out;
		}
		String action = body.has("action") ? body.get("action").getAsString() : "?";
		Claim before = ClaimStore.data().claims.get(body.has("claim") ? body.get("claim").getAsString() : "");
		// What the claim was, so the log can say what changed (and name it even after a delete).
		String label = before == null ? null : "\"" + before.displayName() + "\" (" + (before.owner == null ? "server" : before.ownerName) + ")";
		Snapshot old = before == null ? null : new Snapshot(before);
		Map<String, Object> result = Claims.apply(ClaimStore.data(), ClaimStore.settings(), body, System.currentTimeMillis());
		if (result.containsKey("ok")) {
			ClaimStore.changed();
			String what = describe(action, body, old, before);
			NiceControlCenter.LOGGER.info("Claims: {}{}{} by {} in the panel", label == null ? "" : label + ": ", what, what.isEmpty() ? action : "",
					by == null ? "an admin" : by);
		}
		return result;
	}

	/** A claim's settings before a change, to log only what changed. */
	private record Snapshot(String name, String description, boolean announce, int color, Map<String, Boolean> flags, Map<String, String> members, String owner, String admin) {
		Snapshot(Claim c) {
			this(c.name, c.description, c.announce, c.color, flagMap(c), memberMap(c), c.owner == null ? "server" : c.ownerName, adminText(c.admin));
		}
	}

	private static Map<String, Boolean> flagMap(Claim c) {
		Map<String, Boolean> m = new LinkedHashMap<>();
		if (c.flags != null) for (String[] f : Claims.Flags.LABELS) m.put(f[0], c.flags.get(f[0]));
		return m;
	}

	private static Map<String, String> memberMap(Claim c) {
		Map<String, String> m = new LinkedHashMap<>();
		if (c.members != null) c.members.forEach((uuid, mem) -> m.put(uuid, mem.name == null ? uuid : mem.name));
		return m;
	}

	/** The game-area options as words, e.g. "inventory aside, sent out after 3 deaths". */
	private static String adminText(Claims.AdminOptions a) {
		if (a == null) return "";
		List<String> on = new ArrayList<>();
		if (a.inventory) on.add("inventory aside");
		if (a.removePickups) on.add("pickups vanish on leaving");
		if (a.dropPickupsOnDeath) on.add("pickups drop on death");
		if (a.keepKitOnDeath) on.add("kit kept on death");
		if (a.noHomes) on.add("no homes");
		if (a.deaths > 0) on.add("sent out after " + a.deaths + " deaths");
		if (a.kit != null && !a.kit.isEmpty()) on.add("kit of " + a.kit.size() + " stacks");
		if (a.respawn != null && a.respawn.length >= 3) on.add("respawn point " + (int) Math.floor(a.respawn[0]) + " " + (int) Math.floor(a.respawn[1]) + " " + (int) Math.floor(a.respawn[2]));
		return String.join(", ", on);
	}

	/** What an action changed, in words; empty when there's nothing to tell beyond the action's name. */
	private static String describe(String action, JsonObject body, Snapshot old, Claim c) {
		List<String> out = new ArrayList<>();
		switch (action) {
			case "update", "admin" -> {
				if (old == null) break;
				if (!java.util.Objects.equals(old.name(), c.name)) out.add("renamed from \"" + (old.name() == null ? "" : old.name()) + "\" to \"" + c.displayName() + "\"");
				if (!java.util.Objects.equals(old.description(), c.description)) out.add(c.description.isEmpty() ? "description removed" : "description \"" + c.description + "\"");
				if (old.announce() != c.announce) out.add("title on entering " + (c.announce ? "on" : "off"));
				if (old.color() != c.color) out.add(String.format("colour #%06X → #%06X", old.color(), c.color));
				flagMap(c).forEach((k, v) -> {
					if (!v.equals(old.flags().get(k))) out.add(k + (v ? " on" : " off"));
				});
				String now = adminText(c.admin);
				if (!now.equals(old.admin())) {
					List<String> was = List.of(old.admin().isEmpty() ? new String[0] : old.admin().split(", "));
					List<String> is = List.of(now.isEmpty() ? new String[0] : now.split(", "));
					for (String x : is) if (!was.contains(x)) out.add("game area: " + x);
					for (String x : was) if (!is.contains(x)) out.add("game area: no longer " + x);
				}
				if (out.isEmpty()) out.add(action.equals("admin") ? "game area unchanged" : "nothing changed");
			}
			case "resize" -> out.add("resized to " + num(body, "minX") + " " + num(body, "minZ") + " … " + num(body, "maxX") + " " + num(body, "maxZ"));
			case "delete" -> out.add("deleted");
			case "member_set" -> out.add("member " + text(body, "name") + " set to " + text(body, "role"));
			case "member_remove" -> out.add("member " + (old == null ? text(body, "uuid") : old.members().getOrDefault(text(body, "uuid"), text(body, "uuid"))) + " removed");
			case "keep" -> {
				long until = body.has("until") ? body.get("until").getAsLong() : 0;
				out.add(until <= 0 ? "keep extension removed" : "kept until " + java.time.Instant.ofEpochMilli(until).toString().substring(0, 10));
			}
			case "transfer" -> out.add("given to " + (text(body, "ownerName").isEmpty() ? "the server" : text(body, "ownerName")) + (old == null ? "" : " (was " + old.owner() + ")"));
			case "create" -> out.add("created \"" + text(body, "name") + "\" in " + text(body, "dim") + " at " + num(body, "minX") + " " + num(body, "minZ") + " … " + num(body, "maxX") + " " + num(body, "maxZ")
					+ (text(body, "ownerName").isEmpty() ? " (server)" : " for " + text(body, "ownerName")));
			case "blocks" -> out.add("claim blocks of " + (text(body, "name").isEmpty() ? text(body, "uuid") : text(body, "name")) + " set to " + num(body, "bought") + " bought");
			default -> {
				return "";
			}
		}
		return String.join(", ", out);
	}

	private static String text(JsonObject o, String k) {
		var v = o.get(k);
		return v == null || v.isJsonNull() || !v.isJsonPrimitive() ? "" : v.getAsString().strip();
	}

	private static String num(JsonObject o, String k) {
		String t = text(o, k);
		return t.isEmpty() ? "?" : t;
	}

	/** Claims for the panel's map: rectangles with colour, name and owner. */
	public static List<Map<String, Object>> mapClaims() {
		List<Map<String, Object>> out = new ArrayList<>();
		ClaimStore.reloadSettings();
		if (!ClaimStore.enabled()) return out;
		for (Claim c : ClaimStore.data().claims.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", c.id);
			m.put("dim", c.dim);
			m.put("minX", c.minX);
			m.put("minZ", c.minZ);
			m.put("maxX", c.maxX + 1);
			m.put("maxZ", c.maxZ + 1);
			m.put("name", c.displayName());
			if (!c.description.isEmpty()) m.put("description", c.description);
			m.put("owner", c.ownerName == null ? "Server" : c.ownerName);
			m.put("color", Claims.hex(c.color));
			m.put("sub", c.isSub());
			List<String> members = new ArrayList<>();
			c.members.values().forEach(mem -> members.add(mem.name));
			m.put("members", members);
			out.add(m);
		}
		return out;
	}

	// ---------------------------------------------------------------- GOML import

	private record Goml(String dim, int x, int z, int radius, List<UUID> owners, List<UUID> trusted) {
	}

	private static List<Goml> goml(MinecraftServer server) {
		List<Goml> out = new ArrayList<>();
		if (!FabricLoader.getInstance().isModLoaded("goml")) return out;
		Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		for (ServerLevel level : server.getAllLevels()) {
			ListTag claims = MapExport.claimList(level, root);
			for (int i = 0; i < claims.size(); i++) {
				CompoundTag entry = claims.getCompoundOrEmpty(i);
				CompoundTag claim = entry.contains("Info") ? entry.getCompoundOrEmpty("Info") : entry;
				CompoundTag box = entry.getCompoundOrEmpty("Box");
				BlockPos origin = BlockPos.of(box.getLongOr("OriginPos", claim.getLongOr("Pos", 0)));
				int radius = box.getIntOr("Radius", box.getIntOr("radius", 0));
				out.add(new Goml(ClaimStore.dim(level), origin.getX(), origin.getZ(), radius, uuids(claim.getListOrEmpty("Owners")), uuids(claim.getListOrEmpty("Trusted"))));
			}
		}
		return out;
	}

	private static List<UUID> uuids(ListTag list) {
		List<UUID> out = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) list.getIntArray(i).filter(a -> a.length == 4).ifPresent(a -> out.add(UUIDUtil.uuidFromIntArray(a)));
		return out;
	}

	private static String name(MinecraftServer server, UUID id) {
		return PlayerInspector.name(server, id).orElse(id.toString().substring(0, 8));
	}

	/** GOML's claims as they would be imported, with the reason when one can't be. */
	public static Map<String, Object> gomlPreview(MinecraftServer server, boolean run) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("installed", FabricLoader.getInstance().isModLoaded("goml"));
		Claims.Data data = ClaimStore.data();
		Claims.Settings settings = ClaimStore.settings();
		List<Map<String, Object>> list = new ArrayList<>();
		int imported = 0;
		long now = System.currentTimeMillis();
		for (Goml g : goml(server)) {
			Map<String, Object> m = new LinkedHashMap<>();
			Claims.Box box = Claims.Box.of(g.x - g.radius, g.z - g.radius, g.x + g.radius, g.z + g.radius);
			m.put("dim", g.dim);
			m.put("x", g.x);
			m.put("z", g.z);
			m.put("radius", g.radius);
			m.put("size", box.width());
			String owner = g.owners.isEmpty() ? null : g.owners.get(0).toString();
			m.put("owner", owner == null ? "Server" : name(server, g.owners.get(0)));
			List<String> members = new ArrayList<>();
			// Everyone but the first owner (also GOML's other owners) becomes a builder; the owner can make co-owners later.
			for (int i = 1; i < g.owners.size(); i++) members.add(name(server, g.owners.get(i)));
			for (UUID t : g.trusted) if (!g.owners.contains(t)) members.add(name(server, t));
			m.put("members", members);
			String problem = data.check(g.dim, box, null, owner, null, settings, true);
			if (problem == null && run) {
				Claims.Data.Result r = data.create(owner, owner == null ? null : name(server, g.owners.get(0)), g.dim, box, null, settings, true, now);
				if (r.ok()) {
					for (int i = 1; i < g.owners.size(); i++) data.setMember(r.claim, g.owners.get(i).toString(), name(server, g.owners.get(i)), Claims.Role.BUILD, now);
					for (UUID t : g.trusted) if (!g.owners.contains(t)) data.setMember(r.claim, t.toString(), name(server, t), Claims.Role.BUILD, now);
					if (owner != null) {
						// The land is theirs already: blocks for it so nobody starts in debt.
						Claims.PlayerBlocks p = data.ensurePlayer(owner, r.claim.ownerName, settings);
						data.setBonus(owner, r.claim.ownerName, p.bonus + (int) Math.min(Integer.MAX_VALUE, box.area()));
					}
					imported++;
					m.put("imported", true);
				} else {
					problem = r.error;
				}
			}
			if (problem != null) m.put("problem", problem);
			list.add(m);
		}
		if (run && imported > 0) {
			ClaimStore.changed();
			NiceControlCenter.LOGGER.info("Claims: imported {} GOML claims", imported);
		}
		out.put("claims", list);
		out.put("imported", imported);
		return out;
	}
}
