package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.panel.world.RegionFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Deletes everything outside a square or circle, like Chunky's trim: whole region files when they lie
 * completely outside, single chunks in the regions the edge runs through (the files are packed again so
 * the space is really freed), the same for entities/ and poi/. Optionally keeps chunks players spent
 * time in. Only while the server is stopped, always after a backup.
 */
public final class Trimmer {
	public record Area(String dimension, int x, int z, int radius, boolean circle, int keepVisitedMinutes) {
		boolean insideChunk(int cx, int cz) {
			double dx = cx * 16 + 8 - x;
			double dz = cz * 16 + 8 - z;
			return circle ? dx * dx + dz * dz <= (double) radius * radius : Math.abs(dx) <= radius && Math.abs(dz) <= radius;
		}
	}

	/** What a trim would do. regions: deleted completely; chunks: per region file, the header slots to drop. */
	public record Plan(Area area, String dimId, Path regionDir, List<int[]> regions, Map<String, Set<Integer>> chunks, long chunkCount,
			long bytes, int regionsKept) {
	}

	private static final int MAX_PREVIEW_CHUNKS = 40_000;

	private final MapService map;
	private final Backups backups;
	private final Supervisor server;
	private final Audit audit;
	private volatile boolean running;
	private volatile String phase = "";
	private final List<String> log = new CopyOnWriteArrayList<>();
	private volatile String result;

	public Trimmer(MapService map, Backups backups, Supervisor server, Audit audit) {
		this.map = map;
		this.backups = backups;
		this.server = server;
		this.audit = audit;
	}

	public Plan plan(Area a) throws IOException {
		if (a.radius < 16 || a.radius > 1_000_000) throw new IOException("The radius must be between 16 and 1,000,000 blocks.");
		MapService.Dim dim = map.dimensions().stream().filter(d -> d.id().equals(a.dimension())).findFirst()
				.orElseThrow(() -> new IOException("Unknown dimension " + a.dimension()));
		Path dir = dim.regions();
		List<int[]> regions = new ArrayList<>();
		Map<String, Set<Integer>> chunks = new LinkedHashMap<>();
		long chunkCount = 0;
		long bytes = 0;
		int kept = 0;
		if (!Files.isDirectory(dir)) return new Plan(a, dim.id(), dir, regions, chunks, 0, 0, 0);
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(f -> RegionFile.coords(f.getFileName().toString()) != null).sorted().toList();
		}
		long visitedTicks = a.keepVisitedMinutes() * 1200L;
		for (Path file : files) {
			int[] rc = RegionFile.coords(file.getFileName().toString());
			int inside = 0;
			int outside = 0;
			for (int lz = 0; lz < 32; lz++) for (int lx = 0; lx < 32; lx++) {
				if (a.insideChunk(rc[0] * 32 + lx, rc[1] * 32 + lz)) inside++;
				else outside++;
			}
			if (outside == 0) {
				kept++;
				continue;
			}
			try (RegionFile region = new RegionFile(file)) {
				Set<Integer> drop = new HashSet<>();
				boolean anyKept = false;
				for (int slot : region.existing()) {
					int lx = slot % 32;
					int lz = slot / 32;
					boolean in = a.insideChunk(rc[0] * 32 + lx, rc[1] * 32 + lz);
					if (!in && visitedTicks > 0 && visited(region, lx, lz, visitedTicks)) in = true;
					if (in) anyKept = true;
					else drop.add(slot);
				}
				if (inside == 0 && !anyKept) {
					regions.add(rc);
					bytes += Files.size(file);
					chunkCount += region.existing().size();
				} else if (!drop.isEmpty()) {
					chunks.put(file.getFileName().toString(), drop);
					chunkCount += drop.size();
					// Roughly: the share of the file those chunks take.
					bytes += Files.size(file) * drop.size() / Math.max(1, region.existing().size());
					kept++;
				} else {
					kept++;
				}
			}
		}
		return new Plan(a, dim.id(), dir, regions, chunks, chunkCount, bytes, kept);
	}

	private static boolean visited(RegionFile region, int lx, int lz, long ticks) {
		try {
			Map<String, Object> chunk = region.read(lx, lz);
			return chunk != null && chunk.get("InhabitedTime") instanceof Number n && n.longValue() >= ticks;
		} catch (IOException | RuntimeException e) {
			return true; // Unreadable: rather keep it.
		}
	}

	/** Shows the plan on the admin view of the web map (border in green, what goes in red). */
	public void preview(Plan p) {
		JsonObject o = new JsonObject();
		o.addProperty("dim", p.dimId());
		o.addProperty("shape", p.area().circle() ? "circle" : "square");
		o.addProperty("x", p.area().x());
		o.addProperty("z", p.area().z());
		o.addProperty("radius", p.area().radius());
		o.addProperty("minX", p.area().x() - p.area().radius());
		o.addProperty("maxX", p.area().x() + p.area().radius());
		o.addProperty("minZ", p.area().z() - p.area().radius());
		o.addProperty("maxZ", p.area().z() + p.area().radius());
		JsonArray regions = new JsonArray();
		for (int[] r : p.regions()) {
			JsonArray a = new JsonArray();
			a.add(r[0]);
			a.add(r[1]);
			regions.add(a);
		}
		o.add("regions", regions);
		JsonArray chunks = new JsonArray();
		int count = 0;
		outer:
		for (Map.Entry<String, Set<Integer>> e : p.chunks().entrySet()) {
			int[] rc = RegionFile.coords(e.getKey());
			for (int slot : e.getValue()) {
				if (++count > MAX_PREVIEW_CHUNKS) break outer;
				JsonArray a = new JsonArray();
				a.add(rc[0] * 32 + slot % 32);
				a.add(rc[1] * 32 + slot / 32);
				chunks.add(a);
			}
		}
		o.add("chunks", chunks);
		map.setTrimPreview(o);
	}

	/** Dimensions with their key, world border and spawn, for the World tab. */
	public List<Map<String, Object>> dimensions() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (MapService.Dim d : map.dimensions()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id());
			m.put("key", d.key());
			m.put("name", d.name());
			if (d.info() != null) {
				m.put("border", eu.explorerseden.nicecontrolcenter.Json.GSON.fromJson(d.info().get("border"), Map.class));
				m.put("spawn", eu.explorerseden.nicecontrolcenter.Json.GSON.fromJson(d.info().get("spawn"), Map.class));
			}
			out.add(m);
		}
		return out;
	}

	public void clearPreview() {
		map.setTrimPreview(null);
	}

	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", running);
		out.put("phase", phase);
		out.put("log", log);
		out.put("result", result);
		return out;
	}

	/** Starts the trim in the background. Returns an error, or null. */
	public synchronized String start(Area area, String by) {
		if (running) return "A trim is already running.";
		if (server.state() != Supervisor.State.STOPPED && server.state() != Supervisor.State.CRASHED) return "Stop the server first: the world can only be trimmed while it's off.";
		if (backups.busy()) return "A backup is running; try again in a moment.";
		Plan plan;
		try {
			plan = plan(area);
		} catch (IOException e) {
			return e.getMessage();
		}
		if (plan.regions().isEmpty() && plan.chunks().isEmpty()) return "Nothing lies outside that area.";
		running = true;
		log.clear();
		result = null;
		// Locked before the thread starts, so a pending crash restart can't slip in between.
		if (server.state() == Supervisor.State.CRASHED) server.stop();
		server.lock("The world is being trimmed; the server can start again when that's done.");
		Thread t = new Thread(() -> {
			try {
				result = run(plan, by);
			} catch (IOException | RuntimeException e) {
				result = "Failed: " + e.getMessage() + ". The backup from just before is in Backups.";
				step(result);
			} finally {
				server.unlock();
				phase = "";
				running = false;
			}
		}, "trim");
		t.setDaemon(true);
		t.start();
		return null;
	}

	private String run(Plan plan, String by) throws IOException {
		Area a = plan.area();
		String what = (a.circle() ? "circle" : "square") + " radius " + a.radius() + " around " + a.x() + ", " + a.z() + " in " + plan.dimId();
		audit.log(by, "world.trim", what + ": " + plan.regions().size() + " regions, " + plan.chunkCount() + " chunks", null);
		phase = "Backing up";
		step("Making a backup first");
		String error = backups.create("before trim", by, true);
		if (error != null) return "Stopped: the backup failed (" + error + "), nothing was changed.";
		String backup = backups.lastCreated();
		backups.protect(backup);
		step("Backup " + backup);
		try {
			phase = "Trimming";
			Path dimDir = plan.regionDir().getParent();
			List<Path> folders = List.of(plan.regionDir(), dimDir.resolve("entities"), dimDir.resolve("poi"));
			long freed = 0;
			for (int[] r : plan.regions()) {
				String name = "r." + r[0] + "." + r[1] + ".mca";
				for (Path folder : folders) {
					Path f = folder.resolve(name);
					if (Files.exists(f)) {
						freed += Files.size(f);
						Files.delete(f);
					}
					try (Stream<Path> mcc = Files.isDirectory(folder) ? Files.list(folder) : Stream.empty()) {
						for (Path m : mcc.filter(x -> inRegion(x.getFileName().toString(), r)).toList()) Files.deleteIfExists(m);
					}
				}
			}
			step("Deleted " + plan.regions().size() + " region file" + (plan.regions().size() == 1 ? "" : "s"));
			int done = 0;
			for (Map.Entry<String, Set<Integer>> e : plan.chunks().entrySet()) {
				for (Path folder : folders) freed += RegionFile.rewrite(folder.resolve(e.getKey()), e.getValue());
				if (++done % 25 == 0) step("Trimmed " + done + " of " + plan.chunks().size() + " partly-outside regions");
			}
			step("Removed chunks from " + plan.chunks().size() + " region file" + (plan.chunks().size() == 1 ? "" : "s"));
			map.clearTiles(MapService.key(plan.dimId()));
			map.rerender();
			clearPreview();
			String outcome = "Done: " + plan.chunkCount() + " chunks removed, " + Backups.human(freed) + " freed. Areas outside generate fresh when visited.";
			step(outcome);
			audit.log(by, "world.trim.result", outcome, null);
			return outcome;
		} finally {
			backups.unprotect(backup);
		}
	}

	private static boolean inRegion(String mcc, int[] r) {
		if (!mcc.startsWith("c.") || !mcc.endsWith(".mcc")) return false;
		String[] p = mcc.substring(2, mcc.length() - 4).split("\\.");
		if (p.length != 2) return false;
		try {
			return Math.floorDiv(Integer.parseInt(p[0]), 32) == r[0] && Math.floorDiv(Integer.parseInt(p[1]), 32) == r[1];
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private void step(String text) {
		log.add(text);
		server.note("Trim: " + text);
	}
}
