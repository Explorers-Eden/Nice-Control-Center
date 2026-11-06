package eu.explorerseden.nicecontrolcenter.games;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * An arena's blocks as they were when its game started: put back after each wave and at the end, so whatever the
 * fight left (cobwebs, fire, placed or broken blocks, water or lava) is gone and the arena is as built.
 */
final class ArenaSnapshot {
	/** Bigger boxes aren't kept (too much to hold and compare). */
	private static final long MAX_BLOCKS = 4_000_000L;

	private final ServerLevel level;
	private final int x0, y0, z0, dx, dy, dz;
	private final BlockState[] states;

	private ArenaSnapshot(ServerLevel level, int x0, int y0, int z0, int dx, int dy, int dz) {
		this.level = level;
		this.x0 = x0;
		this.y0 = y0;
		this.z0 = z0;
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.states = new BlockState[dx * dy * dz];
	}

	/** The box's blocks now (null: no box, or too big). */
	static ArenaSnapshot take(ServerLevel level, AABB box, String name) {
		if (level == null || box == null) return null;
		int x0 = (int) Math.floor(box.minX), y0 = Math.max(level.getMinY(), (int) Math.floor(box.minY)), z0 = (int) Math.floor(box.minZ);
		int x1 = (int) Math.ceil(box.maxX), y1 = Math.min(level.getMaxY() + 1, (int) Math.ceil(box.maxY)), z1 = (int) Math.ceil(box.maxZ);
		int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		if (dx <= 0 || dy <= 0 || dz <= 0) return null;
		if ((long) dx * dy * dz > MAX_BLOCKS) {
			NiceControlCenter.LOGGER.info("Games: arena {} is too big ({} blocks) to be put back after its waves", name, (long) dx * dy * dz);
			return null;
		}
		ArenaSnapshot s = new ArenaSnapshot(level, x0, y0, z0, dx, dy, dz);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int y = 0; y < dy; y++) for (int z = 0; z < dz; z++) for (int x = 0; x < dx; x++) s.states[i++] = level.getBlockState(p.set(x0 + x, y0 + y, z0 + z));
		return s;
	}

	/** Every block that changed goes back to how it was; how many. */
	int restore() {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int i = 0, n = 0;
		// Top down: what hangs or stands on others (torches, cobwebs) goes before what holds it.
		for (int y = dy - 1; y >= 0; y--) {
			for (int z = 0; z < dz; z++) {
				for (int x = 0; x < dx; x++) {
					i = (y * dz + z) * dx + x;
					p.set(x0 + x, y0 + y, z0 + z);
					BlockState was = states[i];
					if (level.getBlockState(p) == was) continue;
					level.setBlock(p, was, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					n++;
				}
			}
		}
		return n;
	}
}
