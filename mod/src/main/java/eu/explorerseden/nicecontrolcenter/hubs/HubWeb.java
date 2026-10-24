package eu.explorerseden.nicecontrolcenter.hubs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

/**
 * Waypoint hubs on the website (through the panel, which already checked who the player is): the
 * player's own hubs, and the owner menu's changes (name, description, colour, public or private,
 * trusted players) without standing at the hub. Server thread.
 */
public final class HubWeb {
	private HubWeb() {
	}

	/** The player's hubs, with what the owner menu can change. */
	public static Map<String, Object> list(MinecraftServer server, String uuid) {
		UUID player;
		try {
			player = UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			return Map.of("error", "Unknown player.");
		}
		List<Map<String, Object>> hubs = new ArrayList<>();
		Hubs.ownedHubs(server, player).forEach((id, hub) -> {
			CompoundTag pos = hub.getCompoundOrEmpty("pos");
			Map<String, Object> h = new LinkedHashMap<>();
			h.put("id", id);
			h.put("name", Hubs.name(server, hub));
			h.put("description", Hubs.description(server, hub));
			h.put("color", Hubs.dye(hub));
			h.put("public", Hubs.isPublic(hub));
			h.put("trusted", Hubs.trusted(hub));
			h.put("dimension", Hubs.dimension(hub));
			h.put("x", (int) Math.floor(pos.getDoubleOr("x", 0)));
			h.put("y", (int) Math.floor(pos.getDoubleOr("y", 0)));
			h.put("z", (int) Math.floor(pos.getDoubleOr("z", 0)));
			hubs.add(h);
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hubs", hubs);
		out.put("colors", Hubs.COLORS);
		out.put("maxText", Hubs.MAX_TEXT);
		return out;
	}

	/** Applies the changed fields of one hub the player owns; answers the hub as it is now, or an error. */
	public static Map<String, Object> edit(MinecraftServer server, JsonObject in) {
		UUID player;
		int id;
		try {
			player = UUID.fromString(str(in, "uuid"));
			id = Integer.parseInt(str(in, "id"));
		} catch (IllegalArgumentException e) {
			return Map.of("error", "Unknown hub.");
		}
		CompoundTag hub = Hubs.hub(server, id);
		if (hub == null) return Map.of("error", "That hub doesn't exist any more.");
		if (!Hubs.ownedBy(hub, player)) return Map.of("error", "Only the hub's owner can change it.");

		String name = in.has("name") ? Hubs.clean(str(in, "name")) : null;
		if (name != null && name.isEmpty()) return Map.of("error", "Give the hub a name.");
		String dye = in.has("color") ? str(in, "color") : null;
		if (dye != null && !Hubs.COLORS.containsKey(dye)) return Map.of("error", "Pick one of the colours.");
		List<String> trusted = null;
		if (in.has("trusted") && in.get("trusted").isJsonArray()) {
			trusted = new ArrayList<>();
			for (JsonElement e : in.getAsJsonArray("trusted")) {
				String typed = e.isJsonPrimitive() ? e.getAsString().strip() : "";
				if (typed.isEmpty()) continue;
				// The pack compares names exactly, so a trusted name is spelled as the game knows the player.
				var online = server.getPlayerList().getPlayerByName(typed);
				NameAndId who = online != null ? online.nameAndId() : server.services().nameToIdCache().get(typed).orElse(null);
				if (who == null) return Map.of("error", "Nobody called \"" + typed + "\" has played here.");
				if (who.id().equals(player)) continue;
				if (!trusted.contains(who.name())) trusted.add(who.name());
			}
		}

		var marker = name != null || dye != null ? Hubs.markerAt(server, id, hub) : null;
		if (name != null && !name.equals(Hubs.name(server, hub))) Hubs.rename(server, id, marker, name);
		if (in.has("description")) {
			String description = Hubs.clean(str(in, "description"));
			if (!description.equals(Hubs.description(server, hub))) Hubs.describe(server, id, description);
		}
		if (dye != null && !dye.equals(Hubs.dye(hub))) Hubs.recolor(server, id, marker, dye);
		if (in.has("public")) {
			boolean open = in.get("public").getAsBoolean();
			if (open != Hubs.isPublic(hub)) Hubs.setPublic(server, id, open);
		}
		if (trusted != null && !trusted.equals(Hubs.trusted(hub))) Hubs.setTrusted(server, id, trusted);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("name", Hubs.name(server, Hubs.hub(server, id)));
		return out;
	}

	private static String str(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? "" : v.getAsString().strip();
	}
}
