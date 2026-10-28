package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * Weather for players in a dimension with "always clear sky" (per-dimension tuning): rain starting becomes rain
 * stopping, rain and thunder levels are sent as 0. The weather itself (the overworld's) carries on as usual.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class WeatherPacketMixin {
	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), argsOnly = true)
	private Packet<?> nicecontrolcenter$clearSky(Packet<?> packet, Packet<?> same, ChannelFutureListener listener) {
		if (!(packet instanceof ClientboundGameEventPacket event) || !((Object) this instanceof ServerGamePacketListenerImpl game)) return packet;
		ClientboundGameEventPacket.Type type = event.getEvent();
		boolean weather = type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
				|| type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE;
		if (!weather || game.player == null || !PerfControl.clearSky(game.player.level())) return packet;
		if (type == ClientboundGameEventPacket.START_RAINING) return new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0);
		return new ClientboundGameEventPacket(type, 0);
	}
}
