package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;

import eu.explorerseden.nicecontrolcenter.core.LocatorBar;

/** A moving player's locator bar is worked out a few times a second instead of on every movement packet (see LocatorBar). */
@Mixin(ServerChunkCache.class)
public abstract class LocatorBarThrottleMixin {
	@WrapOperation(method = "move", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/waypoints/ServerWaypointManager;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
	private void nicecontrolcenter$throttle(ServerWaypointManager manager, ServerPlayer player, Operation<Void> original) {
		if (LocatorBar.due(player)) original.call(manager, player);
	}
}
