package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import eu.explorerseden.nicecontrolcenter.panel.world.Nbt;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Copies what a website shows about players from the world into the database (smp_* tables, V9): each
 * player's tags (Fabled Roots race and class), stats and advancements, and the data packs' command storage
 * (waypoint hubs, homes, graves). Checked every minute; only files that changed are read again. The world
 * files stay the source: rows of players whose files are gone are removed.
 */
final class SmpData {
	private static final Set<String> RACES = Set.of("aetherian", "dunesworn", "endling", "frostborne", "moonshroud", "netherian",
			"oakhearted", "orebringer", "palehearted", "turtlekin");
	private static final Set<String> CLASSES = Set.of("archer", "bard", "builder", "cleric", "fighter", "hermit", "miner", "rancher",
			"scout", "survivor");

	private final Path serverDir;
	private final Db db;
	/** File → last modified time already copied (lost on restart: everything is copied once again then). */
	private final Map<Path, Long> copied = new HashMap<>();
	private String copiedLevel = "";

	SmpData(Path serverDir, Db db) {
		this.serverDir = serverDir;
		this.db = db;
	}

	void start() {
		if (db == null) return;
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "smp-data");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(() -> {
			try {
				sync();
			} catch (RuntimeException e) {
				System.err.println("Could not copy the player data to the database: " + e.getMessage());
			}
		}, 30, 60, TimeUnit.SECONDS);
	}

	private synchronized void sync() {
		if (!db.ready()) return;
		String level = ServerTuning.readProperties(serverDir.resolve("server.properties")).getProperty("level-name", "world").strip();
		Path world = serverDir.resolve(level);
		if (!Files.isDirectory(world)) return;
		if (!level.equals(copiedLevel)) {
			copied.clear();
			copiedLevel = level;
		}
		// 26.x keeps players under players/; older worlds have playerdata, stats and advancements at the top.
		Path players = world.resolve("players");
		Path dataDir = Files.isDirectory(players.resolve("data")) ? players.resolve("data") : world.resolve("playerdata");
		Path statsDir = Files.isDirectory(players.resolve("stats")) ? players.resolve("stats") : world.resolve("stats");
		Path advDir = Files.isDirectory(players.resolve("advancements")) ? players.resolve("advancements") : world.resolve("advancements");
		Map<String, String> names = names();
		try (Connection c = db.connection()) {
			Set<String> present = new HashSet<>();
			int changed = 0;
			for (Path dat : list(dataDir, ".dat")) {
				String id = dat.getFileName().toString().replaceFirst("\\.dat$", "");
				UUID uuid;
				try {
					uuid = UUID.fromString(id);
				} catch (IllegalArgumentException e) {
					continue;
				}
				present.add(uuid.toString());
				Path stats = statsDir.resolve(id + ".json");
				Path adv = advDir.resolve(id + ".json");
				if (!changed(dat) && !changed(stats) && !changed(adv)) continue;
				// Times taken before reading: a save during the copy is copied next time.
				long[] times = { modified(dat), modified(stats), modified(adv) };
				if (copyPlayer(c, uuid, names.get(uuid.toString()), dat, stats, adv)) {
					copied.put(dat, times[0]);
					copied.put(stats, times[1]);
					copied.put(adv, times[2]);
					changed++;
				}
			}
			if (!present.isEmpty()) removeGone(c, present);
			for (Map.Entry<String, Path> e : storageFiles(world.resolve("data")).entrySet()) {
				Path file = e.getValue();
				if (!changed(file)) continue;
				long time = modified(file);
				if (copyStorage(c, e.getKey(), file)) copied.put(file, time);
			}
			try (PreparedStatement st = c.prepareStatement("INSERT INTO smp_sync (id, level_name, players, synced_at) VALUES (1, ?, ?, now()) "
					+ "ON CONFLICT (id) DO UPDATE SET level_name = EXCLUDED.level_name, players = EXCLUDED.players, synced_at = EXCLUDED.synced_at")) {
				st.setString(1, level);
				st.setInt(2, present.size());
				st.executeUpdate();
			}
			if (changed > 0) System.out.println("Copied " + changed + " player(s) to the database (smp_players)");
		} catch (SQLException e) {
			System.err.println("Could not copy the player data to the database: " + e.getMessage());
		}
	}

	private boolean copyPlayer(Connection c, UUID uuid, String name, Path dat, Path stats, Path adv) throws SQLException {
		Map<String, Object> nbt = readNbt(dat);
		if (nbt == null) return false; // being written right now: next time
		List<String> tags = new ArrayList<>();
		if (nbt.get("Tags") instanceof List<?> l) for (Object t : l) if (t instanceof String s) tags.add(s);
		String race = null;
		String playerClass = null;
		for (String t : tags) {
			if (!t.startsWith("fabled_roots.")) continue;
			String v = t.substring("fabled_roots.".length()).toLowerCase(Locale.ROOT);
			if (RACES.contains(v)) race = v;
			else if (CLASSES.contains(v)) playerClass = v;
		}
		JsonArray tagJson = new JsonArray();
		tags.forEach(tagJson::add);
		try (PreparedStatement st = c.prepareStatement("INSERT INTO smp_players (player_uuid, player_name, race, class, tags, stats, advancements, data_version, saved_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, now()) ON CONFLICT (player_uuid) DO UPDATE SET player_name = COALESCE(EXCLUDED.player_name, smp_players.player_name), "
				+ "race = EXCLUDED.race, class = EXCLUDED.class, tags = EXCLUDED.tags, stats = EXCLUDED.stats, advancements = EXCLUDED.advancements, "
				+ "data_version = EXCLUDED.data_version, saved_at = EXCLUDED.saved_at, updated_at = now()")) {
			st.setObject(1, uuid);
			st.setString(2, name);
			st.setString(3, race);
			st.setString(4, playerClass);
			st.setString(5, tagJson.toString());
			st.setString(6, jsonFile(stats));
			st.setString(7, jsonFile(adv));
			if (nbt.get("DataVersion") instanceof Integer v) st.setInt(8, v);
			else st.setNull(8, java.sql.Types.INTEGER);
			st.setTimestamp(9, new Timestamp(modified(dat)));
			st.executeUpdate();
		}
		return true;
	}

	private boolean copyStorage(Connection c, String name, Path file) throws SQLException {
		Map<String, Object> nbt = readNbt(file);
		if (nbt == null) return false;
		try (PreparedStatement st = c.prepareStatement("INSERT INTO smp_command_storage (name, data, saved_at, updated_at) VALUES (?, ?::jsonb, ?, now()) "
				+ "ON CONFLICT (name) DO UPDATE SET data = EXCLUDED.data, saved_at = EXCLUDED.saved_at, updated_at = now()")) {
			st.setString(1, name);
			st.setString(2, clean(simplified(nbt).toString()));
			st.setTimestamp(3, new Timestamp(modified(file)));
			st.executeUpdate();
		}
		return true;
	}

	private void removeGone(Connection c, Set<String> present) throws SQLException {
		List<UUID> gone = new ArrayList<>();
		try (PreparedStatement st = c.prepareStatement("SELECT player_uuid FROM smp_players"); var rs = st.executeQuery()) {
			while (rs.next()) {
				UUID u = (UUID) rs.getObject(1);
				if (!present.contains(u.toString())) gone.add(u);
			}
		}
		for (UUID u : gone) {
			try (PreparedStatement st = c.prepareStatement("DELETE FROM smp_players WHERE player_uuid = ?")) {
				st.setObject(1, u);
				st.executeUpdate();
			}
		}
	}

	// ---------------------------------------------------------------- files

	/** Not copied yet, or modified (or gone) since. */
	private boolean changed(Path f) {
		Long had = copied.get(f);
		return had == null || had != modified(f);
	}

	private static long modified(Path f) {
		try {
			return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	private static List<Path> list(Path dir, String suffix) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + suffix)) {
			for (Path f : files) out.add(f);
		} catch (IOException e) {
			// Next time.
		}
		return out;
	}

	/** Command storage per namespace: data/<namespace>/command_storage.dat (26.3+) or data/command_storage_<namespace>.dat. */
	static Map<String, Path> storageFiles(Path data) {
		Map<String, Path> out = new TreeMap<>();
		for (Path f : list(data, ".dat")) {
			String name = f.getFileName().toString();
			if (name.startsWith("command_storage_")) out.put(name.substring("command_storage_".length(), name.length() - ".dat".length()), f);
		}
		if (Files.isDirectory(data)) {
			try (DirectoryStream<Path> dirs = Files.newDirectoryStream(data, Files::isDirectory)) {
				for (Path dir : dirs) {
					Path f = dir.resolve("command_storage.dat");
					if (Files.isRegularFile(f)) out.put(dir.getFileName().toString(), f);
				}
			} catch (IOException e) {
				// Next time.
			}
		}
		return out;
	}

		/** uuid → name from usercache.json. */
	private Map<String, String> names() {
		Map<String, String> out = new HashMap<>();
		try {
			Path f = serverDir.resolve("usercache.json");
			if (!Files.exists(f)) return out;
			for (JsonElement e : JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				if (o.has("uuid") && o.has("name")) out.put(o.get("uuid").getAsString(), o.get("name").getAsString());
			}
		} catch (IOException | RuntimeException e) {
			// No names this time.
		}
		return out;
	}

	/** A JSON file's text for a jsonb column; null when it's missing or not JSON. */
	private static String jsonFile(Path f) {
		try {
			if (!Files.exists(f)) return null;
			return clean(JsonParser.parseString(Files.readString(f)).toString());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Postgres' jsonb can't hold the NUL character. */
	private static String clean(String json) {
		return json.replace("\\u0000", "");
	}

	static Map<String, Object> readNbt(Path f) {
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(f))) {
			raw.mark(2);
			int b1 = raw.read();
			int b2 = raw.read();
			raw.reset();
			InputStream in = b1 == 0x1f && b2 == 0x8b ? new GZIPInputStream(raw) : raw;
			return Nbt.read(new DataInputStream(new BufferedInputStream(in)));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** NBT as JSON the way prismarine-nbt's simplify() gives it, so a website's NBT code works on it unchanged. */
	static JsonElement simplified(Object v) {
		if (v instanceof Map<?, ?> m) {
			JsonObject o = new JsonObject();
			new TreeMap<>(m).forEach((k, x) -> o.add(String.valueOf(k), simplified(x)));
			return o;
		}
		if (v instanceof List<?> l) {
			JsonArray a = new JsonArray();
			for (Object x : l) a.add(simplified(x));
			return a;
		}
		if (v instanceof Long l) return longPair(l);
		if (v instanceof Float f) return f.isNaN() || f.isInfinite() ? JsonNull.INSTANCE : new JsonPrimitive(f);
		if (v instanceof Double d) return d.isNaN() || d.isInfinite() ? JsonNull.INSTANCE : new JsonPrimitive(d);
		if (v instanceof Number n) return new JsonPrimitive(n);
		if (v instanceof String s) return new JsonPrimitive(s);
		JsonArray a = new JsonArray();
		if (v instanceof byte[] b) for (byte x : b) a.add(x);
		else if (v instanceof int[] ia) for (int x : ia) a.add(x);
		else if (v instanceof long[] la) for (long x : la) a.add(longPair(x));
		else return JsonNull.INSTANCE;
		return a;
	}

	private static JsonArray longPair(long l) {
		JsonArray a = new JsonArray();
		a.add((int) (l >> 32));
		a.add((int) l);
		return a;
	}
}
