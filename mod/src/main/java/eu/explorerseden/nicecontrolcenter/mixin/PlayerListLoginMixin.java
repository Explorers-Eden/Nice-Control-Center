package eu.explorerseden.nicecontrolcenter.mixin;

import eu.explorerseden.nicecontrolcenter.web.PanelBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/** Under the panel: players need a linked Discord account (when the panel asks for it). Checked after bans and the whitelist. */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true, require = 0)
	private void nicecontrolcenter$panelLogin(SocketAddress address, NameAndId player, CallbackInfoReturnable<Component> cir) {
		if (cir.getReturnValue() == null) {
			Component refusal = PanelBridge.checkLogin(server, player);
			if (refusal != null) {
				cir.setReturnValue(refusal);
			}
		}
	}
}
