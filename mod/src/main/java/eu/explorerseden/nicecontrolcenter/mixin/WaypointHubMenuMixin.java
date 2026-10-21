package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.hubs.HubDialogs;

/** A hub's owner gets the owner menu instead of Warping Wonders' waypoint list (which that menu opens on request). */
@Mixin(ServerPlayer.class)
public abstract class WaypointHubMenuMixin {
	@Inject(method = "openDialog", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$ownerMenu(Holder<Dialog> dialog, CallbackInfo ci) {
		if (HubDialogs.intercept((ServerPlayer) (Object) this, dialog)) ci.cancel();
	}
}
