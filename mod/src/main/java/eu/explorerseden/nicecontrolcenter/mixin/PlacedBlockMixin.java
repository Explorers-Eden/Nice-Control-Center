package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.pets.PlacedBlocks;

/** Placed ore and stone are remembered, so the pets' Miner leaves them alone. */
@Mixin(BlockItem.class)
public abstract class PlacedBlockMixin {
	@Inject(method = "placeBlock", at = @At("RETURN"))
	private void nicecontrolcenter$placed(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) PlacedBlocks.placed(ctx.getLevel(), ctx.getClickedPos(), state);
	}
}
