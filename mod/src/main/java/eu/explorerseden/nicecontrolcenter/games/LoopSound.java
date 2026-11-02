package eu.explorerseden.nicecontrolcenter.games;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import eu.explorerseden.nicecontrolcenter.Games.Arena;

/**
 * A game's own sound (Arena.loopSound), played again and again to each player in it, only to them and
 * following them (Music/Jukebox volume slider: records). Stopped when they leave the game.
 */
final class LoopSound {
	private record Playing(String arena, String sound, long again) {
	}

	private static final Map<UUID, Playing> playing = new HashMap<>();

	private LoopSound() {
	}

	/** Every tick (from GameRuntime). */
	static void tick(MinecraftServer server) {
		long tick = server.getTickCount();
		if (tick % 10 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Arena a = QuickRuntime.arenaOf(p);
			if (a == null) a = WaveRuntime.arenaOf(p);
			if (a == null) a = GameRuntime.arenaOf(p);
			Playing was = playing.get(p.getUUID());
			String sound = a == null || a.loopSound.isEmpty() || a.loopSeconds < 1 ? null : a.loopSound;
			if (was != null && (sound == null || !sound.equals(was.sound()) || !a.id.equals(was.arena()))) {
				stop(p, was.sound());
				playing.remove(p.getUUID());
				was = null;
			}
			if (sound == null || was != null && tick < was.again()) continue;
			Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
			if (id == null) continue;
			Holder<SoundEvent> event = Holder.direct(SoundEvent.createVariableRangeEvent(id));
			p.connection.send(new ClientboundSoundEntityPacket(event, SoundSource.RECORDS, p, (float) a.loopVolume, 1f, p.getRandom().nextLong()));
			playing.put(p.getUUID(), new Playing(a.id, sound, tick + a.loopSeconds * 20L));
		}
		for (Iterator<UUID> it = playing.keySet().iterator(); it.hasNext();) {
			if (server.getPlayerList().getPlayer(it.next()) == null) it.remove();
		}
	}

	private static void stop(ServerPlayer p, String sound) {
		Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
		if (id != null) p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
	}
}
