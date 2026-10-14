package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Saplings plant themselves: a sapling lying on the ground for a while (dropped by leaves, felling or a
 * player) becomes a sapling block where it lies, if it can grow there and nothing else is too close.
 */
public final class Saplings {
	/** By id: not every version has it as a constant. */
	private static final TagKey<Block> SAPLINGS = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("saplings"));
	private Saplings() {
	}

	/** From the item entity tick mixin, every tick; works once a second. */
	public static void tick(ItemEntity entity) {
		if (!(entity.level() instanceof ServerLevel level) || entity.getAge() % 20 != 0 || !entity.onGround()) return;
		Tweaks.Saplings s = TweaksControl.settings().saplings;
		if (!s.replant || entity.getAge() < s.delaySeconds * 20) return;
		ItemStack stack = entity.getItem();
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item) || !TweaksControl.isReplantable(stack)) return;
		BlockState plant = item.getBlock().defaultBlockState();
		BlockPos pos = spot(level, entity, plant);
		if (pos == null) return;
		if (!s.inClaims && ClaimStore.claimAt(level, pos) != null) return;
		int r = s.spacing;
		if (r > 0) {
			for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
				if (p.equals(pos)) continue;
				BlockState other = level.getBlockState(p);
				if (other.is(SAPLINGS) || other.is(BlockTags.LOGS) || other.is(plant.getBlock())) return;
			}
		}
		level.setBlock(pos, plant, 3);
		level.playSound(null, pos, plant.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1f);
		stack.shrink(1);
		entity.setItem(stack);
	}

	/**
	 * Where the sapling can take root: the block it lies in. A little above its feet, because on mud, soul soil
	 * or a path it lies a bit inside the block below; and also under the corners of the item, because one
	 * lying on the edge of a block has air under its middle. Null when there's no good spot.
	 */
	private static BlockPos spot(ServerLevel level, ItemEntity entity, BlockState plant) {
		double x = entity.getX(), y = entity.getY() + 0.2, z = entity.getZ();
		double w = entity.getBbWidth() / 2;
		BlockPos tried = null;
		for (double[] d : new double[][] {{0, 0}, {-w, -w}, {w, -w}, {-w, w}, {w, w}}) {
			BlockPos pos = BlockPos.containing(x + d[0], y, z + d[1]);
			if (pos.equals(tried)) continue;
			if (fits(level, pos, plant)) return pos;
			tried = pos;
		}
		return null;
	}

	private static boolean fits(ServerLevel level, BlockPos pos, BlockState plant) {
		BlockState here = level.getBlockState(pos);
		if (!here.isAir() && !here.canBeReplaced()) return false;
		if (!here.getFluidState().isEmpty()) return false;
		// Only on the ground set in the panel (not farmland by default), and where the sapling itself can grow.
		if (!TweaksControl.saplingGround(level.getBlockState(pos.below()))) return false;
		return plant.canSurvive(level, pos);
	}
}
