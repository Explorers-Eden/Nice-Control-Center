package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim borders as fake blocks (GriefPrevention style) that only the viewing player sees: corner
 * blocks with a block along each edge, and markers every few blocks along the edges, on the ground near
 * the player's height. The blocks come from the claim settings ("claim_color" = the concrete closest to
 * the claim's colour). Shown in claim mode, for a while after using the tool or opening a claim, and
 * briefly when something is refused. Leaving the area or the time running out puts the real blocks back.
 */
public final class ClaimVisuals {
	private static final int EVERY = 10;
	/** Most fake blocks per player at once. */
	private static final int MAX_BLOCKS = 800;

	private static final class View {
		String dim;
		long until;
		/** Only this claim, or null for every claim nearby. */
		String only;
		/** Fake blocks the client has: position → what it shows. */
		final Map<Long, BlockState> shown = new HashMap<>();
		BlockPos center;
		long rev = -1;
		String settings;
		boolean dirty;
	}

	private static final Map<UUID, View> views = new HashMap<>();

	/** Concrete colours (map colours of the 16 concretes) for "claim_color". */
	private static final int[] CONCRETE_RGB = { 0xCFD5D6, 0xE06101, 0xA9309F, 0x2489C7, 0xF1AF15, 0x5EA918, 0xD5658F, 0x373A3E, 0x7D7D73, 0x157788,
			0x64209C, 0x2D2F8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F };
	private static final String[] DYES = { "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple",
			"blue", "brown", "green", "red", "black" };

	private ClaimVisuals() {
	}

	/** Shows the claims around the player (or just one) for the configured time. */
	public static void show(ServerPlayer player, Claim only) {
		show(player, only, ClaimStore.settings().visualSeconds * 20);
	}

	public static void show(ServerPlayer player, Claim only, int ticks) {
		View v = views.computeIfAbsent(player.getUUID(), k -> new View());
		v.until = player.level().getServer().getTickCount() + ticks;
		v.only = only == null ? null : only.id;
		v.dirty = true;
	}

	/** Draw again soon (the selection or a claim changed). */
	public static void refresh(ServerPlayer player) {
		View v = views.get(player.getUUID());
		if (v != null) v.dirty = true;
		else if (ClaimMode.active(player)) show(player, null, 0);
	}

	/** Puts the real blocks back for the player. */
	public static void hide(ServerPlayer player) {
		View v = views.remove(player.getUUID());
		if (v != null && v.dim != null && v.dim.equals(ClaimStore.dim(player.level()))) revert(player, v.shown.keySet());
	}

	public static void forget(UUID id) {
		views.remove(id);
	}

	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % EVERY != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isRemoved()) continue;
			boolean mode = ClaimMode.active(player);
			View v = views.get(player.getUUID());
			if (v == null) {
				if (!mode) continue;
				v = new View();
				views.put(player.getUUID(), v);
				v.dirty = true;
			}
			if (!mode && v.until < tick) {
				hide(player);
				continue;
			}
			String dim = ClaimStore.dim(player.level());
			if (!dim.equals(v.dim)) {
				// A new dimension: the client forgot the old fake blocks with the old chunks.
				v.shown.clear();
				v.dim = dim;
				v.dirty = true;
			}
			BlockPos here = player.blockPosition();
			String settingsKey = settingsKey();
			boolean moved = v.center == null || v.center.distManhattan(here) > 6;
			boolean changed = v.rev != ClaimStore.data().rev || !settingsKey.equals(v.settings);
			if (!moved && !changed && !v.dirty) continue;
			v.center = here;
			v.rev = ClaimStore.data().rev;
			v.settings = settingsKey;
			v.dirty = false;
			update(player, v, mode ? null : v.only);
		}
	}

	private static String settingsKey() {
		Claims.Settings s = ClaimStore.settings();
		return s.visualCorner + s.visualEdge + s.visualSubCorner + s.visualSubEdge + s.visualSelection + s.visualSpacing + s.visualRange;
	}

	private static void update(ServerPlayer player, View v, String only) {
		ServerLevel level = player.level();
		Claims.Settings s = ClaimStore.settings();
		int range = s.visualRange;
		Map<Long, BlockState> want = new LinkedHashMap<>();
		List<Claim> claims;
		if (only != null) {
			Claim c = ClaimStore.byId(only);
			claims = c != null && c.dim.equals(v.dim) ? List.of(c) : List.of();
		} else {
			claims = ClaimStore.near(v.dim, player.getBlockX(), player.getBlockZ(), range);
		}
		for (Claim c : claims) addClaim(level, player, c, s, range, want);
		List<BlockState> sel = state(s.visualSelection, 0xFFFFFF);
		for (int[] m : ClaimTool.markers(player)) put(level, player, m[0], m[1], sel, range, want);
		// Back to real blocks where nothing is wanted any more.
		List<Long> gone = v.shown.keySet().stream().filter(k -> !want.containsKey(k)).toList();
		revert(player, gone);
		gone.forEach(v.shown::remove);
		want.forEach((k, state) -> {
			if (state.equals(v.shown.get(k))) return;
			player.connection.send(new ClientboundBlockUpdatePacket(BlockPos.of(k), state));
			v.shown.put(k, state);
		});
	}

	private static void addClaim(ServerLevel level, ServerPlayer player, Claim c, Claims.Settings s, int range, Map<Long, BlockState> want) {
		List<BlockState> corner = state(c.isSub() ? s.visualSubCorner : s.visualCorner, c.color);
		List<BlockState> edge = state(c.isSub() ? s.visualSubEdge : s.visualEdge, c.color);
		int step = s.visualSpacing;
		// Edges first, so corners win where they meet.
		for (int x = c.minX + step; x < c.maxX - 1; x += step) {
			put(level, player, x, c.minZ, edge, range, want);
			put(level, player, x, c.maxZ, edge, range, want);
		}
		for (int z = c.minZ + step; z < c.maxZ - 1; z += step) {
			put(level, player, c.minX, z, edge, range, want);
			put(level, player, c.maxX, z, edge, range, want);
		}
		int[][] corners = { { c.minX, c.minZ, 1, 1 }, { c.maxX, c.minZ, -1, 1 }, { c.minX, c.maxZ, 1, -1 }, { c.maxX, c.maxZ, -1, -1 } };
		for (int[] k : corners) {
			put(level, player, k[0], k[1], corner, range, want);
			if (c.maxX > c.minX) put(level, player, k[0] + k[2], k[1], edge, range, want);
			if (c.maxZ > c.minZ) put(level, player, k[0], k[1] + k[3], edge, range, want);
		}
	}

	/** A marker: one of the blocks, always the same for that spot (lists and tags mix along the border). */
	private static void put(ServerLevel level, ServerPlayer player, int x, int z, List<BlockState> states, int range, Map<Long, BlockState> want) {
		BlockState state = states.get(Math.floorMod(x * 31 + z * 17, states.size()));
		if (want.size() >= MAX_BLOCKS) return;
		double dx = x + 0.5 - player.getX();
		double dz = z + 0.5 - player.getZ();
		if (dx * dx + dz * dz > (double) range * range) return;
		BlockPos ground = ground(level, x, player.getBlockY(), z);
		if (ground != null) want.put(ground.asLong(), state);
	}

	/**
	 * The ground block near the player's height: the first solid block with room above it, looking
	 * down from a little above the player (so borders show in caves too), else the surface.
	 */
	private static BlockPos ground(ServerLevel level, int x, int y, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
		if (!level.isLoaded(pos)) return null;
		int top = Math.min(y + 12, level.getMaxY());
		int bottom = Math.max(y - 24, level.getMinY());
		BlockState above = level.getBlockState(pos.setY(top + 1));
		for (int yy = top; yy >= bottom; yy--) {
			BlockState here = level.getBlockState(pos.setY(yy));
			if (!here.isAir() && !here.canBeReplaced() && (above.isAir() || above.canBeReplaced())) return pos.immutable();
			above = here;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		if (surface < level.getMinY() || Math.abs(surface - y) > 64) return null;
		return new BlockPos(x, surface, z);
	}

	private static void revert(ServerPlayer player, Iterable<Long> positions) {
		ServerLevel level = player.level();
		for (long k : positions) {
			BlockPos pos = BlockPos.of(k);
			if (level.isLoaded(pos)) player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
		}
	}

	/** Blocks from the settings (ids and #tags, comma-separated), or the concrete closest to {@code rgb} for "claim_color". */
	private static List<BlockState> state(String id, int rgb) {
		if (Claims.CLAIM_COLOR.equals(id)) return List.of(nearestConcrete(rgb).defaultBlockState());
		return eu.explorerseden.nicecontrolcenter.core.BlockLists.of(id, nearestConcrete(rgb)).stream().map(Block::defaultBlockState).toList();
	}

	private static Block nearestConcrete(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int best = 0;
		long bestDist = Long.MAX_VALUE;
		for (int i = 0; i < CONCRETE_RGB.length; i++) {
			int c = CONCRETE_RGB[i];
			long dr = r - ((c >> 16) & 0xFF);
			long dg = g - ((c >> 8) & 0xFF);
			long db = b - (c & 0xFF);
			// Weighted for how eyes see colour.
			long dist = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(DYES[best] + "_concrete"));
		return block == null ? Blocks.STONE : block;
	}
}
