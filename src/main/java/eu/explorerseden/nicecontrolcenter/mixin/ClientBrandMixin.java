package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.players.ClientInfo;

/** Remembers the client brand ("fabric", "vanilla", "lunarclient", …) each player sends when joining. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ClientBrandMixin {
	@Inject(method = "handleCustomPayload", at = @At("HEAD"))
	private void nicecontrolcenter$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof BrandPayload brand) {
			var owner = ((ServerCommonPacketListenerImpl) (Object) this).getOwner();
			if (owner != null) {
				ClientInfo.brand(owner.id(), brand.brand());
			}
		}
	}
}
