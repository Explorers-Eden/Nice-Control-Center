package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** What a nether portal frame may be made of, as vanilla decides it in this version: obsidian (this version has no frame tag yet). */
public final class PortalFrames {
	private PortalFrames() {
	}

	public static boolean isFrame(BlockState state) {
		return state.is(Blocks.OBSIDIAN);
	}
}
