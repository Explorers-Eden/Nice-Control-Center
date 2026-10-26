package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/** Beds and respawn anchors don't set a spawn point inside game claims (no homes there). */
@Mixin(ServerPlayer.class)
public abstract class GameClaimSpawnMixin {
	@Inject(method = "setRespawnPosition", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noSpawnInGame(ServerPlayer.RespawnConfig config, boolean showMessage, CallbackInfo ci) {
		if (config == null) return;
		ServerPlayer self = (ServerPlayer) (Object) this;
		var server = self.level().getServer();
		if (server == null) return;
		var level = server.getLevel(config.respawnData().dimension());
		if (level != null && GameClaims.blocksHomes(level, config.respawnData().pos())) {
			if (showMessage) self.sendOverlayMessage(Component.literal("You can't set your spawn point here").withStyle(ChatFormatting.RED));
			ci.cancel();
		}
	}
}
