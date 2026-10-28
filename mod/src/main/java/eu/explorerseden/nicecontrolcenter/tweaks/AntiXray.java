package eu.explorerseden.nicecontrolcenter.tweaks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Tweaks;

/**
 * Hides ores from x-ray clients (Paper's anti-xray, engine mode 1). Chunks sent to players carry copies of the
 * sections in which every hidden ore without an open side (all six neighbours occlude) is plain stone, deepslate,
 * netherrack or end stone. When a block changes, the ores next to it are sent again as they are, so players see
 * an ore as soon as they uncover it. The world itself is never changed. Server thread.
 */
public final class AntiXray {
	private static volatile Set<Block> hidden = Set.of();
	private static volatile boolean warned;
	/** The chunk whose sections were just worked out: the packet asks for its size and then for its data. */
	private static final ThreadLocal<Object[]> last = new ThreadLocal<>();

	private AntiXray() {
	}

	/**
	 * Which blocks of a section were found covered (packed y<<8 | z<<4 | x), per real section (weak keys: dropped with
	 * the chunk). Only the positions are kept: every send copies the live section and covers a position only if an ore
	 * is still there, so nothing but the expensive search is reused. Cleared around every block change, and whole when
	 * the settings change.
	 */
	private static final Map<LevelChunkSection, short[]> cache = Collections.synchronizedMap(new WeakHashMap<>());
	private static final short[] NOTHING = new short[0];
	/** Above this many cached sections the cache starts over. */
	private static final int CACHE_MAX = 50_000;

	/** After the settings are read: the blocks to hide, from the ids and #tags. */
	static void rebuild(Tweaks.Settings s, List<TweaksControl.Match<Block>> blocks) {
		cache.clear();
		if (!s.antiXray.enabled) {
			hidden = Set.of();
			return;
		}
		Set<Block> set = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Block block : BuiltInRegistries.BLOCK) {
			if (TweaksControl.block(blocks, block.defaultBlockState())) set.add(block);
		}
		set.remove(Blocks.AIR);
		hidden = set;
	}

	public static boolean active() {
		return !hidden.isEmpty();
	}

	/** The sections to send for this chunk: the real ones, or copies with hidden ores where needed. */
	public static LevelChunkSection[] sections(LevelChunk chunk, LevelChunkSection[] real) {
		Set<Block> hide = hidden;
		if (hide.isEmpty() || !(chunk.getLevel() instanceof ServerLevel level) || eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.dungeonWorld(level)) return real;
		Object[] cached = last.get();
		if (cached != null && cached[0] == chunk && cached[1] == real) {
			last.remove();
			return (LevelChunkSection[]) cached[2];
		}
		LevelChunkSection[] out = real;
		try {
			boolean overworldLike = !level.dimension().equals(Level.NETHER) && !level.dimension().equals(Level.END);
			int maxY = TweaksControl.settings().antiXray.maxY;
			for (int i = 0; i < real.length; i++) {
				LevelChunkSection section = real[i];
				if (section == null || section.hasOnlyAir()) continue;
				int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
				if (overworldLike && baseY > maxY) continue;
				if (!section.maybeHas(state -> hide.contains(state.getBlock()))) continue;
				short[] covered = cache.get(section);
				if (covered == null) {
					covered = coveredIn(level, chunk, real, i, baseY, overworldLike ? maxY : Integer.MAX_VALUE, hide);
					if (cache.size() >= CACHE_MAX) cache.clear();
					cache.put(section, covered);
				}
				LevelChunkSection copy = hideAt(level, section, covered, hide);
				if (copy != null) {
					if (out == real) out = real.clone();
					out[i] = copy;
				}
			}
		} catch (RuntimeException e) {
			if (!warned) {
				warned = true;
				NiceControlCenter.LOGGER.warn("Anti-xray: hiding ores failed; chunks are sent as they are", e);
			}
			out = real;
		}
		last.set(new Object[] { chunk, real, out });
		return out;
	}

	/** The section's ores that nothing shows (packed y<<8 | z<<4 | x); the expensive part, so it's cached. */
	private static short[] coveredIn(ServerLevel level, LevelChunk chunk, LevelChunkSection[] sections, int index, int baseY, int maxY, Set<Block> hide) {
		LevelChunkSection section = sections[index];
		short[] found = new short[64];
		int n = 0;
		int top = Math.min(15, maxY - baseY);
		for (int y = 0; y <= top; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (!hide.contains(state.getBlock()) || exposed(level, chunk, sections, index, x, y, z)) continue;
					if (n == found.length) found = java.util.Arrays.copyOf(found, n * 2);
					found[n++] = (short) (y << 8 | z << 4 | x);
				}
			}
		}
		return n == 0 ? NOTHING : java.util.Arrays.copyOf(found, n);
	}

	/** A copy of the live section with the covered ores that are still there replaced, or null when there's nothing to hide. */
	private static LevelChunkSection hideAt(ServerLevel level, LevelChunkSection section, short[] covered, Set<Block> hide) {
		LevelChunkSection copy = null;
		for (short p : covered) {
			int x = p & 15, z = p >> 4 & 15, y = p >> 8 & 15;
			BlockState state = section.getBlockState(x, y, z);
			if (!hide.contains(state.getBlock())) continue;
			if (copy == null) copy = section.copy();
			copy.setBlockState(x, y, z, cover(level, state), false);
		}
		return copy;
	}

	/** Whether any side of the block touches something that doesn't hide it (air, water, glass, leaves…). */
	private static boolean exposed(ServerLevel level, LevelChunk chunk, LevelChunkSection[] sections, int index, int x, int y, int z) {
		LevelChunkSection section = sections[index];
		for (Direction d : Direction.values()) {
			int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
			BlockState next;
			if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && ny >= 0 && ny < 16) {
				next = section.getBlockState(nx, ny, nz);
			} else if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
				int other = index + (ny < 0 ? -1 : 1);
				// Above the top or below the bottom of the world: nothing to see through.
				if (other < 0 || other >= sections.length) continue;
				LevelChunkSection s = sections[other];
				next = s == null ? Blocks.AIR.defaultBlockState() : s.getBlockState(nx, ny & 15, nz);
			} else {
				// In the next chunk, if it's loaded (only asked on the server thread; elsewhere it counts as covered).
				if (!level.getServer().isSameThread()) continue;
				int cx = chunk.getPos().x() + (nx < 0 ? -1 : nx > 15 ? 1 : 0);
				int cz = chunk.getPos().z() + (nz < 0 ? -1 : nz > 15 ? 1 : 0);
				LevelChunk neighbour = level.getChunkSource().getChunkNow(cx, cz);
				if (neighbour == null) continue;
				LevelChunkSection s = neighbour.getSections()[index];
				next = s == null ? Blocks.AIR.defaultBlockState() : s.getBlockState(nx & 15, y, nz & 15);
			}
			if (!next.canOcclude()) return true;
		}
		return false;
	}

	private static void forget(ServerLevel level, BlockPos pos) {
		if (cache.isEmpty() || !level.isLoaded(pos)) return;
		LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null) return;
		int index = chunk.getSectionIndex(pos.getY());
		LevelChunkSection[] sections = chunk.getSections();
		if (index >= 0 && index < sections.length && sections[index] != null) cache.remove(sections[index]);
	}

	/** What a hidden ore looks like: the stone around it. */
	private static BlockState cover(ServerLevel level, BlockState ore) {
		if (level.dimension().equals(Level.NETHER)) return Blocks.NETHERRACK.defaultBlockState();
		if (level.dimension().equals(Level.END)) return Blocks.END_STONE.defaultBlockState();
		String id = BuiltInRegistries.BLOCK.getKey(ore.getBlock()).getPath();
		if (id.contains("deepslate")) return Blocks.DEEPSLATE.defaultBlockState();
		if (id.contains("nether") || ore.is(Blocks.ANCIENT_DEBRIS)) return Blocks.NETHERRACK.defaultBlockState();
		return Blocks.STONE.defaultBlockState();
	}

	/** A block changed: hidden ores next to it are sent to players again, as they are. */
	public static void changed(ServerLevel level, BlockPos pos) {
		Set<Block> hide = hidden;
		if (hide.isEmpty()) return;
		// What's covered may have changed in this section and the ones touching the block.
		forget(level, pos);
		for (Direction d : Direction.values()) forget(level, pos.relative(d));
		for (Direction d : Direction.values()) {
			BlockPos next = pos.relative(d);
			// Never load a chunk for this.
			if (!level.isLoaded(next)) continue;
			if (hide.contains(level.getBlockState(next).getBlock())) level.getChunkSource().blockChanged(next);
		}
	}
}
