package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;

import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * A Loyalty trident that falls into the void comes back instead of vanishing: it starts returning as if it
 * had hit something, and loyalty pulls it up through the void to its owner.
 */
@Mixin(Entity.class)
public abstract class TridentVoidMixin {
	@Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$return(CallbackInfo ci) {
		if (!((Object) this instanceof ThrownTrident trident) || trident.level().isClientSide()) return;
		if (!TweaksControl.settings().tridents.voidReturn) return;
		if (trident.getEntityData().get(ThrownTridentAccessor.nicecontrolcenter$loyalty()) <= 0) return;
		if (!(trident.getOwner() instanceof ServerPlayer owner) || !owner.isAlive() || owner.level() != trident.level()) return;
		((ThrownTridentAccessor) trident).nicecontrolcenter$setDealtDamage(true);
		ci.cancel();
	}
}
