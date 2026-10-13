package eu.explorerseden.nicecontrolcenter.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The little chicken-egg "plop" that comes with a message, like in the Explorer's Eden data packs
 * (playsound entity.chicken.egg neutral @s ~ ~ ~ .6 2). Only that player hears it, at most every half second.
 */
public final class Plop {
	private static final Map<UUID, Long> last = new ConcurrentHashMap<>();

	private Plop() {
	}

	public static void play(ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long before = last.put(player.getUUID(), now);
		if (before != null && now - before < 500) return;
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.CHICKEN_EGG), SoundSource.NEUTRAL,
				player.getX(), player.getY(), player.getZ(), 0.6f, 2f, player.getRandom().nextLong()));
	}
}
