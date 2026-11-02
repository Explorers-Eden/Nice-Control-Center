package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.npcs.NpcRuntime;

/** NPCs (their bodies and the hidden mobs behind them) never get into boats, minecarts or onto anything else but their own seat. */
@Mixin(Entity.class)
public abstract class NpcNoRidingMixin {
	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noRiding(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
		var tags = ((Entity) (Object) this).entityTags();
		// Except a sitting NPC onto its own seat.
		if ((tags.contains(NpcRuntime.BODY) || tags.contains(NpcRuntime.DRIVER)) && !vehicle.entityTags().contains(NpcRuntime.SEAT)) cir.setReturnValue(false);
	}
}
