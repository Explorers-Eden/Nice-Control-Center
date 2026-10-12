package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Water and lava don't flow from outside into someone's claim. */
@Mixin(FlowingFluid.class)
public abstract class ClaimFluidMixin {
	/** Every spread check (sideways and down, and the slope search) goes through here. */
	@Inject(method = "canMaybePassThrough", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$claims(BlockGetter getter, BlockPos from, BlockState fromState, Direction dir, BlockPos to, BlockState toState,
			FluidState toFluid, CallbackInfoReturnable<Boolean> cir) {
		if (getter instanceof Level level && !level.isClientSide() && ClaimProtection.crossingBlocked(level, from, to)) cir.setReturnValue(false);
	}
}
