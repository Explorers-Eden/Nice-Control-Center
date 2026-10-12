package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Arrows only press wooden buttons in a claim for players who may use its buttons. */
@Mixin(ButtonBlock.class)
public abstract class ClaimButtonMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$claims(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean intersects, CallbackInfo ci) {
		if (!level.isClientSide() && entity instanceof Projectile p && !ClaimProtection.projectileAllowed(p, pos, Claims.Role.ACCESS)) ci.cancel();
	}
}
