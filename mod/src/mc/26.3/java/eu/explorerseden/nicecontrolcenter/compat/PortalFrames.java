package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/** What a nether portal frame may be made of, as vanilla decides it in this version: the block tag minecraft:nether_portal_frame (obsidian unless a data pack adds more). */
public final class PortalFrames {
	private PortalFrames() {
	}

	public static boolean isFrame(BlockState state) {
		return state.is(BlockTags.NETHER_PORTAL_FRAME);
	}
}
