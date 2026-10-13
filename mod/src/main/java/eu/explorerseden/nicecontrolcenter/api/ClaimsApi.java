package eu.explorerseden.nicecontrolcenter.api;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Land claims for other mods (Merl uses it by reflection, so it works without a compile dependency).
 * Server thread. Kept small and stable: plain maps, no internal types.
 */
public final class ClaimsApi {
	private ClaimsApi() {
	}

	/** Claims protect right now. */
	public static boolean enabled() {
		return ClaimStore.enabled();
	}

	/**
	 * The claims (and subclaims) a player owns or is a member of, each as
	 * {name, dimension, minX, minZ, maxX, maxZ (inclusive), owner (UUID string or null), ownerName, mine (Boolean), role, color ("#RRGGBB"), sub (Boolean)}.
	 */
	public static List<Map<String, Object>> claimsOf(UUID player) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!ClaimStore.enabled()) return out;
		Claims.Data data = ClaimStore.data();
		String id = player.toString();
		for (Claims.Claim c : data.claims.values()) {
			Claims.Role role = data.roleOf(c, id);
			if (role == null) continue;
			Map<String, Object> m = new java.util.LinkedHashMap<>();
			m.put("name", c.displayName());
			m.put("dimension", c.dim);
			m.put("minX", c.minX);
			m.put("minZ", c.minZ);
			m.put("maxX", c.maxX);
			m.put("maxZ", c.maxZ);
			m.put("owner", c.owner);
			m.put("ownerName", c.ownerName == null ? "the server" : c.ownerName);
			m.put("mine", role == Claims.Role.OWNER);
			m.put("role", role.label);
			m.put("color", Claims.hex(c.color));
			m.put("sub", c.isSub());
			out.add(m);
		}
		return out;
	}
}
