package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Farmland in a claim isn't trampled by players who can't build there (or by mobs, unless allowed). */
@Mixin(FarmlandBlock.class)
public abstract class ClaimFarmlandMixin {
	@Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noTrampling(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
		if (level.isClientSide() || ClaimProtection.tramplingAllowed(level, pos, entity)) return;
		// What Block.fallOn does: the fall still hurts.
		entity.causeFallDamage(fallDistance, 1.0F, entity.damageSources().fall());
		ci.cancel();
	}
}
