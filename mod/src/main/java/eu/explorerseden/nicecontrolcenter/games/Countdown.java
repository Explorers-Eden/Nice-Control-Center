package eu.explorerseden.nicecontrolcenter.games;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** The last five seconds of a game's timer tick (one sound a second, higher at the end), for whoever plays. */
final class Countdown {
	/** Timer → the second it last ticked for. */
	private static final Map<String, Long> last = new ConcurrentHashMap<>();

	private Countdown() {
	}

	/** Called often with the time left; ticks once at 5, 4, 3, 2 and 1 seconds left. */
	static void tick(String timer, long msLeft, Collection<ServerPlayer> players) {
		if (msLeft <= 0 || msLeft > 5_000) {
			if (msLeft > 5_000) last.remove(timer);
			return;
		}
		long second = (msLeft + 999) / 1000;
		if (Long.valueOf(second).equals(last.put(timer, second))) return;
		float pitch = second == 1 ? 2.0f : 1.4f;
		// Only to them (not everyone standing near).
		for (ServerPlayer p : players) {
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_HAT, SoundSource.PLAYERS, p.getX(), p.getY(), p.getZ(), 1f, pitch, p.getRandom().nextLong()));
		}
	}

	/** The timer is over (or gone): forget it. */
	static void done(String timer) {
		last.remove(timer);
	}
}
