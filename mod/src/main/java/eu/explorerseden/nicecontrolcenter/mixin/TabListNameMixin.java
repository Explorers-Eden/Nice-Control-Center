package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.tweaks.AfkTabList;

/** The AFK tag in the tab list (the team-styled name with " AFK"; teams themselves are left alone). */
@Mixin(ServerPlayer.class)
public abstract class TabListNameMixin {
	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$afk(CallbackInfoReturnable<Component> cir) {
		Component name = AfkTabList.displayName((ServerPlayer) (Object) this);
		if (name != null) cir.setReturnValue(name);
	}
}
