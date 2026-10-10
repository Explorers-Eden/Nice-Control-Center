package eu.explorerseden.nicecontrolcenter.mixin;

import eu.explorerseden.nicecontrolcenter.log.ChatLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Messages to everyone while nobody is online (with players online, {@link ServerPlayerChatMixin} sees them). */
@Mixin(PlayerList.class)
public abstract class PlayerListChatMixin {
	@Shadow
	public abstract int getPlayerCount();

	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V", at = @At("HEAD"), require = 0)
	private void nicecontrolcenter$system(Component message, Function<ServerPlayer, Component> perPlayer, boolean overlay, CallbackInfo ci) {
		if (!overlay && getPlayerCount() == 0) {
			ChatLog.unheard(message, false);
		}
	}

	@Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V",
			at = @At("HEAD"), require = 0)
	private void nicecontrolcenter$command(PlayerChatMessage message, CommandSourceStack source, ChatType.Bound type, CallbackInfo ci) {
		if (getPlayerCount() == 0) {
			ChatLog.unheard(type.decorate(message.decoratedContent()), true);
		}
	}
}
