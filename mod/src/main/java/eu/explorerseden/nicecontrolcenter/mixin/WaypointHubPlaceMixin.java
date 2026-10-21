package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.hubs.Hubs;

/** Waypoint hubs placed outside the permanent area never become hubs: the item drops again. */
@Mixin(ServerLevel.class)
public abstract class WaypointHubPlaceMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$hubPlace(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (Hubs.refusePlacement((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
	}
}
