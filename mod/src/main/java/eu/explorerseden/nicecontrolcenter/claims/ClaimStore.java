package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The claims while the server runs: the world file in memory, an index by chunk for the protection
 * checks (which run for every block a player touches, so they must be cheap), and the settings from
 * the panel. Everything here runs on the server thread.
 */
public final class ClaimStore {
	/** Saved at most this often (ticks) after a change, and when the server stops. */
	private static final int SAVE_EVERY = 100;

	private static Claims.Data data = new Claims.Data().fill();
	private static Claims.Settings settings = new Claims.Settings().fill();
	private static long settingsModified = -1;
	private static Path file;
	private static MinecraftServer server;
	private static boolean dirty;
	/** Dimension → chunk key → claims (not subclaims) touching that chunk. */
	private static final Map<String, Map<Long, List<Claim>>> index = new HashMap<>();
	/** Claim id → its subclaims. */
	private static final Map<String, List<Claim>> children = new HashMap<>();

	private ClaimStore() {
	}

	public static Claims.Data data() {
		return data;
	}

	public static Claims.Settings settings() {
		return settings;
	}

	/** Claims protect and the tool works. */
	public static boolean enabled() {
		return settings.enabled;
	}

	public static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Claims.FILE);
	}

	/** Hooks claims into the server: events, ticks, joins and leaves, the /claim command. */
	public static void register() {
		ClaimProtection.register();
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> ClaimCommand.register(dispatcher));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(ClaimStore::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (ClaimStore.server == null) return;
			tick(s);
			ClaimMode.tick(s);
			ClaimVisuals.tick(s);
			ClaimPlaytime.tick(s);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (ClaimStore.server == null) return;
			ClaimMode.joined(handler.player);
			ClaimPlaytime.joined(handler.player);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			var p = handler.player;
			if (ClaimStore.server != null && ClaimMode.active(p)) ClaimMode.exit(p, null);
			ClaimPlaytime.left(p);
			ClaimVisuals.forget(p.getUUID());
			ClaimTool.forget(p.getUUID());
			ClaimProtection.forget(p.getUUID());
		});
		// The claim tool goes before the inventory drops (and the kept item comes back into it).
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof net.minecraft.server.level.ServerPlayer p && ClaimStore.server != null && ClaimMode.active(p)) ClaimMode.exit(p, null);
			return true;
		});
	}

	public static MinecraftServer server() {
		return server;
	}

	public static void load(MinecraftServer server) {
		ClaimStore.server = server;
		file = server.getWorldPath(LevelResource.ROOT).resolve(Claims.DATA_FILE);
		try {
			data = Claims.readData(file);
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Couldn't read {}; claims start empty and the file is kept as .broken", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Nothing more to do.
			}
			data = new Claims.Data().fill();
		}
		settingsModified = -1;
		reloadSettings();
		reindex();
		dirty = false;
		if (!data.claims.isEmpty()) NiceControlCenter.LOGGER.info("{} claims loaded{}", data.claims.size(), settings.enabled ? "" : " (claims are off)");
	}

	public static void stop() {
		// Everyone leaves claim mode before players are saved, so kept items are in their inventories.
		MinecraftServer s = server;
		if (s != null) for (var p : s.getPlayerList().getPlayers()) if (ClaimMode.active(p)) ClaimMode.exit(p, null);
		save();
		eu.explorerseden.nicecontrolcenter.core.AsyncFiles.flush();
		file = null;
		server = null;
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 100 == 0) reloadSettings();
		// The permanent area for the claim checks (read again by HardBorder when the file changes).
		data.borders = eu.explorerseden.nicecontrolcenter.core.HardBorder.borders();
		if (dirty && tick % SAVE_EVERY == 0) save();
	}

	/**
	 * Reads the settings again if the panel changed them. Ticks do this every 5 seconds, but a server
	 * that pauses while empty doesn't tick, so the dashboard API asks too (server thread).
	 */
	public static void reloadSettings() {
		Path f = settingsFile();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(f);
		} catch (IOException e) {
			return;
		}
		if (now == settingsModified) return;
		boolean first = settingsModified < 0;
		settingsModified = now;
		boolean wasOn = settings.enabled;
		settings = Claims.read(f);
		if (!first && wasOn != settings.enabled) {
			NiceControlCenter.LOGGER.info("Claims are now {}", settings.enabled ? "on" : "off");
			if (!settings.enabled) ClaimMode.endAll();
		}
	}

	/** Marks the data changed (index rebuilt, saved soon). */
	public static void changed() {
		reindex();
		dirty = true;
	}

	/** Only player numbers changed (no claim moved): saved soon, no new index. */
	public static void touched() {
		dirty = true;
	}

	/** Saves right away (handed to the file writer at once; claim mode keeps an item of the player's: it must not wait for the next save). */
	public static void saveNow() {
		dirty = true;
		save();
	}

	public static void save() {
		Path f = file;
		if (f == null || !dirty) return;
		// Turned into text here (the data only changes on this thread), written to disk in the background.
		eu.explorerseden.nicecontrolcenter.core.AsyncFiles.write(f, eu.explorerseden.nicecontrolcenter.Json.GSON.toJson(data));
		dirty = false;
	}

	/** Replaces everything (the panel imported or restored claims). */
	public static void replace(Claims.Data fresh) {
		data = fresh.fill();
		changed();
	}

	private static void reindex() {
		index.clear();
		children.clear();
		for (Claim c : data.claims.values()) {
			if (c.parent != null) {
				children.computeIfAbsent(c.parent, k -> new ArrayList<>()).add(c);
				continue;
			}
			Map<Long, List<Claim>> chunks = index.computeIfAbsent(c.dim, k -> new HashMap<>());
			for (int cx = c.minX >> 4; cx <= c.maxX >> 4; cx++) {
				for (int cz = c.minZ >> 4; cz <= c.maxZ >> 4; cz++) {
					chunks.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(1)).add(c);
				}
			}
		}
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	/** The claim (not subclaim) at a spot, or null. */
	public static Claim topAt(String dim, int x, int z) {
		Map<String, Map<Long, List<Claim>>> idx = index;
		Map<Long, List<Claim>> chunks = idx.get(dim);
		if (chunks == null) return null;
		List<Claim> list = chunks.get(key(x >> 4, z >> 4));
		if (list == null) return null;
		for (Claim c : list) if (c.contains(x, z)) return c;
		return null;
	}

	/** The innermost claim at a spot (a subclaim when there is one), or null. */
	public static Claim claimAt(String dim, int x, int z) {
		Claim top = topAt(dim, x, z);
		if (top == null) return null;
		List<Claim> subs = children.get(top.id);
		if (subs != null) for (Claim s : subs) if (s.contains(x, z)) return s;
		return top;
	}

	public static Claim claimAt(Level level, BlockPos pos) {
		return claimAt(dim(level), pos.getX(), pos.getZ());
	}

	public static List<Claim> subclaims(String id) {
		return children.getOrDefault(id, List.of());
	}

	/** Claims (and subclaims) with a part within {@code range} blocks of a spot. */
	public static List<Claim> near(String dim, int x, int z, int range) {
		List<Claim> out = new ArrayList<>();
		Map<Long, List<Claim>> chunks = index.get(dim);
		if (chunks == null) return out;
		java.util.Set<Claim> seen = new java.util.HashSet<>();
		for (int cx = (x - range) >> 4; cx <= (x + range) >> 4; cx++) {
			for (int cz = (z - range) >> 4; cz <= (z + range) >> 4; cz++) {
				List<Claim> list = chunks.get(key(cx, cz));
				if (list == null) continue;
				for (Claim c : list) {
					if (!seen.add(c)) continue;
					out.add(c);
					out.addAll(subclaims(c.id));
				}
			}
		}
		return out;
	}

	public static Claim byId(String id) {
		return id == null ? null : data.claims.get(id);
	}
}
