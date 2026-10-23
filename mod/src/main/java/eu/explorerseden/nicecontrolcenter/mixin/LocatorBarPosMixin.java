package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.core.LocatorBar;

/**
 * The second place vanilla works out a moving player's whole locator bar: every position change (Entity.setPosRaw),
 * besides the chunk tracker's move. Shares LocatorBar's timer with LocatorBarThrottleMixin, so it's one update per few ticks in all.
 */
@Mixin(Entity.class)
public abstract class LocatorBarPosMixin {
	@WrapOperation(method = "setPosRaw(DDD)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/waypoints/ServerWaypointManager;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
	private void nicecontrolcenter$throttle(ServerWaypointManager manager, ServerPlayer player, Operation<Void> original) {
		if (LocatorBar.due(player)) original.call(manager, player);
	}
}
