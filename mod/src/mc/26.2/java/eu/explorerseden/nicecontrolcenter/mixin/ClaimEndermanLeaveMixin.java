package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Endermen don't put blocks down in claims unless the claim allows mob griefing. */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")
public abstract class ClaimEndermanLeaveMixin {
	@Inject(method = "canPlaceBlock", at = @At("RETURN"), cancellable = true)
	private void nicecontrolcenter$claims(Level level, BlockPos pos, BlockState carried, BlockState at, BlockState below, BlockPos belowPos,
			CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !ClaimProtection.mobGriefingAllowed(level, pos)) cir.setReturnValue(false);
	}
}
