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
 * A game's music (Arena.loopSong, or a sound id set before songs), played again and again to each player in it,
 * only to them and following them (Music/Jukebox volume slider: records), and its spectator music (spectateSong)
 * to everyone in its spectator area. Stopped when they leave.
 */
final class LoopSound {
	private record Playing(String arena, String sound, long again, int entity) {
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
			// In a game's spectator area (watching, queued, or out for the wave) its spectator music wins.
			Arena watching = watching(p);
			String key;
			String songId;
			String oldSound = "";
			int oldSeconds = 0;
			double volume;
			if (watching != null) {
				key = "watch:" + watching.id;
				songId = watching.spectateSong;
				volume = watching.spectateVolume;
			} else if (a != null) {
				key = a.id;
				songId = a.loopSong;
				oldSound = a.loopSound;
				oldSeconds = a.loopSeconds;
				volume = a.loopVolume;
			} else {
				key = null;
				songId = "";
				volume = 0;
			}
			Playing was = playing.get(p.getUUID());
			// A jukebox song (its own sound and length), else a sound id with the length given (set before songs).
			net.minecraft.world.item.JukeboxSong song = songId.isEmpty() ? null : song(server, songId);
			String sound = song != null ? song.soundEvent().value().location().toString()
					: oldSound.isEmpty() || oldSeconds < 1 ? null : oldSound;
			long length = song != null ? Math.max(20, song.lengthInTicks()) : oldSeconds * 20L;
			// Left the game or the spectator area (or into another one): stopped at once, like /stopsound.
			if (was != null && (sound == null || !sound.equals(was.sound()) || !key.equals(was.arena()))) {
				stop(p, was.sound());
				playing.remove(p.getUUID());
				was = null;
			}
			// A respawn or another dimension makes a new player entity, which ends the song on the client: start it again.
			if (was != null && was.entity() != body(p)) {
				stop(p, was.sound());
				was = null;
			}
			if (sound == null || was != null && tick < was.again()) continue;
			Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
			if (id == null) continue;
			Holder<SoundEvent> event = song != null ? song.soundEvent() : Holder.direct(SoundEvent.createVariableRangeEvent(id));
			p.connection.send(new ClientboundSoundEntityPacket(event, SoundSource.RECORDS, p, (float) volume, 1f, p.getRandom().nextLong()));
			playing.put(p.getUUID(), new Playing(key, sound, tick + length, body(p)));
		}
		for (Iterator<UUID> it = playing.keySet().iterator(); it.hasNext();) {
			if (server.getPlayerList().getPlayer(it.next()) == null) it.remove();
		}
	}

	/** The player's entity on the client: a respawn or a dimension change makes a new one (and ends sounds on the old). */
	private static int body(ServerPlayer p) {
		return p.getId() * 31 + p.level().dimension().hashCode();
	}

	/** The game whose spectator area (with spectator music) the player stands in; null = none. */
	private static Arena watching(ServerPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || a.spectateSong.isEmpty() || a.spectate == null) continue;
			if (eu.explorerseden.nicecontrolcenter.Games.inSpectate(a, dim, p.getX(), p.getY(), p.getZ(), 1)) return a;
		}
		return null;
	}

	private static net.minecraft.world.item.JukeboxSong song(MinecraftServer server, String id) {
		Identifier i = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (i == null) return null;
		return server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.JUKEBOX_SONG)
				.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.JUKEBOX_SONG, i)).map(Holder::value).orElse(null);
	}

	/** Every jukebox song the server knows (vanilla and datapacks), with its name and length, for the panel's list. */
	static void exportSongs(MinecraftServer server) {
		java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
		server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.JUKEBOX_SONG).listElements().forEach(h -> {
			java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
			m.put("id", h.key().identifier().toString());
			m.put("name", h.value().description().getString());
			m.put("seconds", Math.round(h.value().lengthInSeconds()));
			out.add(m);
		});
		out.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(java.util.Locale.ROOT)));
		try {
			java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(eu.explorerseden.nicecontrolcenter.Games.Arena.SONGS);
			java.nio.file.Files.writeString(f, eu.explorerseden.nicecontrolcenter.Json.GSON.toJson(out));
		} catch (java.io.IOException e) {
			eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.warn("Games: could not write the jukebox song list: {}", e.getMessage());
		}
	}

	private static void stop(ServerPlayer p, String sound) {
		Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
		if (id != null) p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
	}
}
