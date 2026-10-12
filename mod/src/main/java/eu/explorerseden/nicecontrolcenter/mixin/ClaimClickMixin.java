package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.claims.ClaimDialogs;

/** Buttons in the claim dialogs ("nicecontrolcenter:claim/…") are answered here, with the player who clicked. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ClaimClickMixin {
	@Inject(method = "handleCustomClickAction", cancellable = true,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"))
	private void nicecontrolcenter$claimDialog(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		if (!"nicecontrolcenter".equals(packet.id().getNamespace()) || !packet.id().getPath().startsWith("claim/")) return;
		ci.cancel();
		if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
			ClaimDialogs.handle(game.player, packet.id().getPath().substring("claim/".length()), packet.payload());
		}
	}
}
