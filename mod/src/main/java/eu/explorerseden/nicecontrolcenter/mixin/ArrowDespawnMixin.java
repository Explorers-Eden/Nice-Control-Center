package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Arrows players can't pick up (shot by mobs) leave the ground sooner; players' own keep vanilla's minute. */
@Mixin(AbstractArrow.class)
public abstract class ArrowDespawnMixin {
	@Shadow
	public AbstractArrow.Pickup pickup;

	@Shadow
	private int life;

	@Inject(method = "tickDespawn", at = @At("TAIL"))
	private void nicecontrolcenter$stray(CallbackInfo ci) {
		if (pickup == AbstractArrow.Pickup.ALLOWED) return;
		int ticks = PerfControl.strayArrowTicks();
		if (ticks > 0 && life >= ticks) ((AbstractArrow) (Object) this).discard();
	}
}
