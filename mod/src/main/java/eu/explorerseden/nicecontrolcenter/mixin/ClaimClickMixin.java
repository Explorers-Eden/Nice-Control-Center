package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.claims.ClaimDialogs;

/** Buttons in the claim, market, pet and waypoint hub dialogs ("nicecontrolcenter:claim/…", "…:market/…", "…:pets/…", "…:hubs/…") are answered here, with the player who clicked. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ClaimClickMixin {
	@Inject(method = "handleCustomClickAction", cancellable = true,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"))
	private void nicecontrolcenter$claimDialog(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		String path = packet.id().getPath();
		if (!"nicecontrolcenter".equals(packet.id().getNamespace()) || !(path.startsWith("claim/") || path.startsWith("market/") || path.startsWith("pets/") || path.startsWith("hubs/") || path.startsWith("shop/"))) return;
		ci.cancel();
		if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
			if (path.startsWith("claim/")) ClaimDialogs.handle(game.player, path.substring("claim/".length()), packet.payload());
			else if (path.startsWith("hubs/")) eu.explorerseden.nicecontrolcenter.hubs.HubDialogs.handle(game.player, path.substring("hubs/".length()), packet.payload());
			else if (path.startsWith("shop/")) eu.explorerseden.nicecontrolcenter.players.RewardShops.handle(game.player, path.substring("shop/".length()), packet.payload());
			else if (path.startsWith("pets/")) eu.explorerseden.nicecontrolcenter.pets.PetDialogs.handle(game.player, path.substring("pets/".length()), packet.payload());
			else eu.explorerseden.nicecontrolcenter.market.MarketDialogs.handle(game.player, path.substring("market/".length()), packet.payload());
		}
	}
}
