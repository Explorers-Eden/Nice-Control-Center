package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Pets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The public waypoint hubs of the Explorer's Eden data packs (command storage database.waypoints.hubs), for
 * the public info page: name, description, owner, place and the owner's face. Read again only when the
 * storage file changed (the game saves it with the world).
 */
final class WaypointHubs {
	private final Path serverDir;
	private Path readFile;
	private long readModified = -1;
	private long checkedAt;
	private List<Map<String, Object>> hubs = List.of();
	private Set<String> heads = Set.of();

	WaypointHubs(Path serverDir) {
		this.serverDir = serverDir;
	}

	/** The public hubs, sorted by dimension and name; empty when there are none. */
	synchronized List<Map<String, Object>> publicHubs() {
		refresh();
		return hubs;
	}

	/** Whether a skin hash is the face of a public hub's owner (so the info page may show it). */
	synchronized boolean isOwnerHead(String hash) {
		refresh();
		return heads.contains(hash);
	}

	private void refresh() {
		String level = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("level-name", "world").strip();
		// Unchanged since the last read: nothing to do (checked only every 10 seconds).
		long now = System.currentTimeMillis();
		if (readFile != null && now - checkedAt < 10_000) return;
		checkedAt = now;
		if (readFile != null && Files.exists(readFile) && modified(readFile) == readModified && readFile.startsWith(serverDir.resolve(level))) return;
		Path file = null;
		Map<String, Object> hubMap = null;
		// The storage that holds database.waypoints.hubs (the "eden" one of the Explorer's Eden packs).
		for (Path f : SmpData.storageFiles(serverDir.resolve(level).resolve("data")).values()) {
			long modified = modified(f);
			Map<String, Object> root = SmpData.readNbt(f);
			Map<String, Object> found = path(root, "data", "contents", "database", "waypoints", "hubs");
			if (found != null) {
				file = f;
				hubMap = found;
				readFile = f;
				readModified = modified;
				break;
			}
		}
		if (file == null) {
			readFile = null;
			hubs = List.of();
			heads = Set.of();
			return;
		}
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> faces = new HashSet<>();
		for (Object o : hubMap.values()) {
			if (!(o instanceof Map<?, ?> raw)) continue;
			@SuppressWarnings("unchecked")
			Map<String, Object> hub = (Map<String, Object>) raw;
			if (!"public".equals(string(hub, "access"))) continue;
			Map<String, Object> pos = map(hub.get("pos"));
			Map<String, Object> profile = map(hub.get("profile"));
			Map<String, Object> h = new LinkedHashMap<>();
			String name = string(hub, "waypoint_name");
			h.put("name", name == null || name.isBlank() ? "Unnamed waypoint" : name.strip());
			String description = string(hub, "waypoint_description");
			h.put("description", description == null ? "" : description.strip());
			h.put("owner", profile == null || string(profile, "name") == null ? "" : string(profile, "name"));
			String dimension = string(hub, "dimension_name");
			if ((dimension == null || dimension.isBlank()) && pos != null) dimension = string(pos, "dimension");
			h.put("dimension", dimension == null || dimension.isBlank() ? "Unknown" : dimension);
			h.put("x", pos == null ? 0 : number(pos.get("x")));
			h.put("y", pos == null ? 0 : number(pos.get("y")));
			h.put("z", pos == null ? 0 : number(pos.get("z")));
			String face = Pets.skinHash(string(hub, "icon"));
			if (face == null && profile != null && profile.get("properties") instanceof List<?> props) {
				for (Object p : props) {
					Map<String, Object> prop = map(p);
					if (prop != null && "textures".equals(string(prop, "name"))) face = Pets.skinHash(string(prop, "value"));
				}
			}
			if (face != null) {
				h.put("head", face);
				faces.add(face);
			}
			out.add(h);
		}
		out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("dimension"))).thenComparing(m -> String.valueOf(m.get("name")).toLowerCase()));
		hubs = List.copyOf(out);
		heads = Set.copyOf(faces);
	}

	private static long modified(Path f) {
		try {
			return Files.getLastModifiedTime(f).toMillis();
		} catch (java.io.IOException e) {
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	private static Map<String, Object> path(Map<String, Object> root, String... keys) {
		Map<String, Object> m = root;
		for (String k : keys) {
			if (m == null) return null;
			m = map(m.get(k));
		}
		return m;
	}

	private static String string(Map<String, Object> m, String key) {
		return m.get(key) instanceof String s ? s : null;
	}

	private static long number(Object o) {
		return o instanceof Number n ? Math.round(n.doubleValue()) : 0;
	}
}
