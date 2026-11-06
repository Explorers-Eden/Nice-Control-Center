package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.games.Moles;

/**
 * Whack-a-mole: in adventure mode the client never sends hits on blocks, only the arm swing; a swing at a head
 * (what the player looks at, within reach) counts as hitting it.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MoleSwingMixin {
	@Shadow
	public ServerPlayer player;

	// TAIL: past the hand-over to the server thread.
	@Inject(method = "handleAnimate", at = @At("TAIL"))
	private void nicecontrolcenter$whack(ServerboundSwingPacket packet, CallbackInfo ci) {
		Moles.swing(player);
	}
}
