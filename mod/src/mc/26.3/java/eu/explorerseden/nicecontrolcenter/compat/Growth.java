package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;

/** One bone meal's growth, as a player using bone meal would give it (this version passes where it comes from). */
public final class Growth {
	private Growth() {
	}

	public static boolean canGrow(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, pos, state, BonemealSource.INTERACTION);
	}

	public static void grow(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof BonemealableBlock b) b.performBonemeal(level, level.getRandom(), pos, state, BonemealSource.INTERACTION);
	}
}
