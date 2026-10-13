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
		BlockPos pos = entity.blockPosition();
		BlockState here = level.getBlockState(pos);
		if (!here.isAir() && !here.canBeReplaced()) return;
		if (!here.getFluidState().isEmpty()) return;
		BlockState plant = item.getBlock().defaultBlockState();
		if (!plant.canSurvive(level, pos)) return;
		if (!s.inClaims && ClaimStore.claimAt(level, pos) != null) return;
		int r = s.spacing;
		if (r > 0) {
			for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
				BlockState other = level.getBlockState(p);
				if (other.is(SAPLINGS) || other.is(BlockTags.LOGS) || other.is(plant.getBlock())) return;
			}
		}
		level.setBlock(pos, plant, 3);
		level.playSound(null, pos, plant.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1f);
		stack.shrink(1);
		entity.setItem(stack);
	}
}
