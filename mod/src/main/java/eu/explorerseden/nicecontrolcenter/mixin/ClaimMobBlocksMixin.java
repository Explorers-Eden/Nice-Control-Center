package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Mobs that break blocks themselves (the wither, ravagers, …) leave claims alone unless mob griefing is allowed there. */
@Mixin(Level.class)
public abstract class ClaimMobBlocksMixin {
	@Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$claims(BlockPos pos, boolean drop, Entity breaker, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		if (breaker instanceof Mob && !((Level) (Object) this).isClientSide() && !ClaimProtection.mobGriefingAllowed((Level) (Object) this, pos)) {
			cir.setReturnValue(false);
		}
	}
}
