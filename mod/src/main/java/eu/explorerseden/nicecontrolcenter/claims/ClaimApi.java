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
		Map<String, Object> out = ClaimStore.data().changes(since);
		out.put("enabled", ClaimStore.enabled());
		return out;
	}

	public static Map<String, Object> action(JsonObject body, String by) {
		Map<String, Object> result = Claims.apply(ClaimStore.data(), ClaimStore.settings(), body, System.currentTimeMillis());
		if (result.containsKey("ok")) {
			ClaimStore.changed();
			NiceControlCenter.LOGGER.info("Claims: {} by {} in the panel", body.has("action") ? body.get("action").getAsString() : "?", by == null ? "an admin" : by);
		}
		return result;
	}

	/** Claims for the panel's map: rectangles with colour, name and owner. */
	public static List<Map<String, Object>> mapClaims() {
		List<Map<String, Object>> out = new ArrayList<>();
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
			for (int i = 1; i < g.owners.size(); i++) members.add(name(server, g.owners.get(i)) + " (co-owner)");
			for (UUID t : g.trusted) if (!g.owners.contains(t)) members.add(name(server, t));
			m.put("members", members);
			String problem = data.check(g.dim, box, null, owner, null, settings, true);
			if (problem == null && run) {
				Claims.Data.Result r = data.create(owner, owner == null ? null : name(server, g.owners.get(0)), g.dim, box, null, settings, true, now);
				if (r.ok()) {
					for (int i = 1; i < g.owners.size(); i++) data.setMember(r.claim, g.owners.get(i).toString(), name(server, g.owners.get(i)), Claims.Role.MANAGE, now);
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
			NiceControlCenter.LOGGER.info("Imported {} GOML claims", imported);
		}
		out.put("claims", list);
		out.put("imported", imported);
		return out;
	}
}
