package eu.explorerseden.nicecontrolcenter.mixin;

import eu.explorerseden.nicecontrolcenter.log.ChatLog;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Everything that lands in a player's chat window, for the dashboard's Chat tab. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {
	@Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), require = 0)
	private void nicecontrolcenter$system(Component message, boolean overlay, CallbackInfo ci) {
		if (!overlay) {
			ChatLog.received((ServerPlayer) (Object) this, message, false);
		}
	}

	@Inject(method = "sendChatMessage", at = @At("HEAD"), require = 0)
	private void nicecontrolcenter$chat(OutgoingChatMessage message, boolean filtered, ChatType.Bound type, CallbackInfo ci) {
		ChatLog.received((ServerPlayer) (Object) this, type.decorate(message.content()), true);
	}
}
