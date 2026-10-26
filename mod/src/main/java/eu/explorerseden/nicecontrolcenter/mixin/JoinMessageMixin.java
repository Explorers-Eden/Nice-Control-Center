package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import eu.explorerseden.nicecontrolcenter.players.Perks;

/** The own join message reward: "X joined the game" becomes the player's own line (the panel and Discord get it too). */
@Mixin(PlayerList.class)
public abstract class JoinMessageMixin {
	@WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void nicecontrolcenter$ownJoinLine(PlayerList list, Component message, boolean overlay, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
		String line = Perks.joinLine(player.getStringUUID(), player.getGameProfile().name());
		if (line == null) {
			original.call(list, message, overlay);
			return;
		}
		original.call(list, Component.literal(line).withStyle(ChatFormatting.YELLOW), overlay);
		eu.explorerseden.nicecontrolcenter.web.PanelBridge.customJoin(player.level().getServer(), player.getGameProfile().name(), line);
	}
}
