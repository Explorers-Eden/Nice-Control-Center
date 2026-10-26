package eu.explorerseden.nicecontrolcenter.players;

import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each player first joined, last logged in and last left, kept in the world folder
 * (nicecontrolcenter-seen.json, so backups carry it). Players from before the mod kept this get an
 * estimate for their first join: when their stats or save file was created, if the file system knows.
 */
public final class Seen {
	public static final class Entry {
		public long first;
		/** First join estimated from file dates (players from before this record). */
		public boolean firstEstimated;
		public long lastJoin;
		public long lastLeave;
	}

	private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
	private static volatile Path file;

	private Seen() {
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-seen.json");
		entries.clear();
		try {
			if (Files.exists(file)) {
				Map<String, Entry> read = Json.GSON.fromJson(Files.readString(file), new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
				if (read != null) entries.putAll(read);
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Couldn't read {}: {}", file, e.toString());
		}
	}

	/** Records the join; true for a player's very first time on the server (no record and no older save files). */
	public static boolean joined(MinecraftServer server, UUID id) {
		long now = System.currentTimeMillis();
		boolean[] first = { false };
		Entry e = entries.computeIfAbsent(id.toString(), k -> {
			Entry fresh = new Entry();
			long guess = earliestFile(server, id);
			// A save file older than a minute means they played before this record started.
			fresh.first = guess > 0 && guess < now - 60_000 ? guess : now;
			fresh.firstEstimated = fresh.first != now;
			first[0] = !fresh.firstEstimated;
			return fresh;
		});
		e.lastJoin = now;
		save();
		return first[0];
	}

	public static void left(UUID id) {
		Entry e = entries.get(id.toString());
		if (e == null) return;
		e.lastLeave = System.currentTimeMillis();
		save();
	}

	/** The record, or an estimate from the files for players never seen by it. */
	public static Map<String, Object> view(MinecraftServer server, UUID id) {
		Map<String, Object> out = new LinkedHashMap<>();
		Entry e = entries.get(id.toString());
		if (e != null) {
			out.put("firstJoin", e.first);
			if (e.firstEstimated) out.put("firstJoinEstimated", true);
			if (e.lastJoin > 0) out.put("lastJoin", e.lastJoin);
			if (e.lastLeave > 0) out.put("lastLeave", e.lastLeave);
			return out;
		}
		long guess = earliestFile(server, id);
		if (guess > 0) {
			out.put("firstJoin", guess);
			out.put("firstJoinEstimated", true);
		}
		return out;
	}

	/** When the player's stats or save file was created; 0 when the file system doesn't keep creation dates. */
	private static long earliestFile(MinecraftServer server, UUID id) {
		long best = 0;
		for (Path p : new Path[] { server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"),
				server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"),
				server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json") }) {
			try {
				if (!Files.exists(p)) continue;
				BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
				long created = a.creationTime().toMillis();
				// Without real creation dates, Java reports the last change: no use as a first join.
				if (created <= 0 || created >= a.lastModifiedTime().toMillis()) continue;
				if (best == 0 || created < best) best = created;
			} catch (IOException e) {
				// Next file.
			}
		}
		return best;
	}

	private static synchronized void save() {
		Path f = file;
		if (f == null) return;
		try {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(entries), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Couldn't save {}: {}", f, e.toString());
		}
	}
}
