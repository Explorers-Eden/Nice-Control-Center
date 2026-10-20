package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.pets.PlayerDrops;

/** Pets' Fetch: what drops while a player uses something on an entity (shears on a sheep…) is theirs. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DropCauseInteractMixin {
	@Shadow
	public ServerPlayer player;

	/** The packet first arrives on a network thread (which hands it on and stops there); only the server thread's run counts. */
	@Inject(method = "handleInteract", at = @At("HEAD"))
	private void nicecontrolcenter$start(ServerboundInteractPacket packet, CallbackInfo ci) {
		if (player.level().getServer().isSameThread()) PlayerDrops.push(player);
	}

	@Inject(method = "handleInteract", at = @At("RETURN"))
	private void nicecontrolcenter$end(ServerboundInteractPacket packet, CallbackInfo ci) {
		if (player.level().getServer().isSameThread()) PlayerDrops.pop();
	}
}
