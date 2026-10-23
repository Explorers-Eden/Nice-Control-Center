package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** While an enchantment or a data pack acts for someone (see ClaimProtection.actorMayChange), claimed blocks they can't build on stay. */
@Mixin(Level.class)
public abstract class ClaimActorEditMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$claims(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
		if (!ClaimProtection.actorMayChange((Level) (Object) this, pos)) cir.setReturnValue(false);
	}
}
