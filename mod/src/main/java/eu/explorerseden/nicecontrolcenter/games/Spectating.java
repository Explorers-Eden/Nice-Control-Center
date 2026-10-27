package eu.explorerseden.nicecontrolcenter.games;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Players who are out watch through a teammate who's still in (no flying around), for dungeons and wave arenas. */
final class Spectating {
	private Spectating() {
	}

	/** Each watcher (in spectator mode) is kept on one of {@code in}; the next one when theirs is out. */
	static void follow(MinecraftServer server, Collection<UUID> watchers, List<ServerPlayer> in) {
		for (UUID id : watchers) {
			ServerPlayer w = server.getPlayerList().getPlayer(id);
			if (w == null || !w.isSpectator()) continue;
			if (in.isEmpty()) {
				if (w.getCamera() != w) w.setCamera(w);
				continue;
			}
			if (w.getCamera() instanceof ServerPlayer seen && in.contains(seen)) continue;
			ServerPlayer target = in.get(Math.floorMod(id.hashCode(), in.size()));
			w.teleportTo((ServerLevel) target.level(), target.getX(), target.getY(), target.getZ(), Set.of(), target.getYRot(), target.getXRot(), true);
			w.setCamera(target);
			w.sendOverlayMessage(Component.literal("Watching " + target.getGameProfile().name()).withStyle(ChatFormatting.GRAY));
		}
	}
}
