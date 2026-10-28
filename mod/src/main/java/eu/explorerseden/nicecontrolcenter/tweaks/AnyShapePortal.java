package eu.explorerseden.nicecontrolcenter.tweaks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import eu.explorerseden.nicecontrolcenter.compat.PortalFrames;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Nether portals in any shape: any area closed by frame blocks (obsidian, or in 26.3 whatever the
 * minecraft:nether_portal_frame tag holds) in one upright plane (a circle, an arch, a
 * heart…) lights like a rectangle does, and stays lit while its frame is whole. Vanilla handles
 * rectangles as always; this only takes over where vanilla finds no portal.
 */
public final class AnyShapePortal {
	private AnyShapePortal() {
	}

	public static boolean enabled() {
		return TweaksControl.settings().portals.anyShape;
	}

	public static boolean inPortalDimension(Level level) {
		return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
	}

	/** The same frame blocks as vanilla portals (in 26.3 the minecraft:nether_portal_frame tag). */
	private static boolean isFrame(BlockState state) {
		return PortalFrames.isFrame(state);
	}

	private static boolean isEmpty(BlockState state) {
		return state.isAir() || state.getBlock() instanceof BaseFireBlock;
	}

	/**
	 * The blocks of the area around start in the plane of axis, or null when it isn't closed by frame blocks
	 * (or bigger than allowed). inside: what may fill the area.
	 */
	static List<BlockPos> area(BlockGetter level, BlockPos start, Direction.Axis axis, Predicate<BlockState> inside) {
		int max = TweaksControl.settings().portals.maxBlocks;
		Direction[] dirs = axis == Direction.Axis.X ? new Direction[] { Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST }
				: new Direction[] { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH };
		List<BlockPos> found = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			BlockState state = level.getBlockState(pos);
			if (!inside.test(state)) {
				if (isFrame(state)) continue;
				return null; // Open: the area leaks out.
			}
			found.add(pos);
			if (found.size() > max) return null;
			for (Direction d : dirs) {
				BlockPos next = pos.relative(d);
				if (seen.add(next)) queue.add(next);
			}
		}
		return found.isEmpty() ? null : found;
	}

	/** Can fire placed here light a portal (for flint and steel against a side of the frame)? */
	public static boolean canLight(Level level, BlockPos pos) {
		if (!enabled() || !inPortalDimension(level) || eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.dungeonWorld(level)) return false;
		return area(level, pos, Direction.Axis.X, AnyShapePortal::isEmpty) != null || area(level, pos, Direction.Axis.Z, AnyShapePortal::isEmpty) != null;
	}

	/** Lights a portal in the closed area around pos (the narrower plane first). Returns whether it did. */
	public static boolean light(LevelAccessor level, BlockPos pos) {
		List<BlockPos> x = area(level, pos, Direction.Axis.X, AnyShapePortal::isEmpty);
		List<BlockPos> z = area(level, pos, Direction.Axis.Z, AnyShapePortal::isEmpty);
		List<BlockPos> blocks = x == null ? z : z == null ? x : x.size() <= z.size() ? x : z;
		if (blocks == null) return false;
		Direction.Axis axis = blocks == x ? Direction.Axis.X : Direction.Axis.Z;
		BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
		// Like vanilla: no block updates while filling, so the portal doesn't break itself half-built.
		for (BlockPos p : blocks) level.setBlock(p, portal, 18);
		return true;
	}

	/** A portal block that vanilla would remove (its frame isn't a rectangle): keep it while its area is still closed. */
	public static boolean stillClosed(BlockGetter level, BlockPos pos, Direction.Axis axis) {
		return area(level, pos, axis, s -> s.is(Blocks.NETHER_PORTAL) && s.getValue(NetherPortalBlock.AXIS) == axis) != null;
	}
}
