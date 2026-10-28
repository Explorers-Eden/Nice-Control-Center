package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.Borders;
import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells players when they cross the soft border (config/nicecontrolcenter-wilds.json, see Borders.Wilds):
 * an action bar message with a plop, and while they're outside a post effect from the server resource pack.
 * Follows the borders as the panel saves them. Players who just joined get the post effect put right
 * without a message, and lose post effects that were set up before (previousIds). Post effects exist from Minecraft 26.3 on and are called by reflection, so older
 * targets just skip them.
 */
public final class OuterWilds {
	private static final int EVERY = 20;
	private static final int RELOAD_EVERY = 100;

	private static volatile Borders.Wilds wilds = new Borders.Wilds();
	/** previousIds parsed, without the current one. */
	private static volatile List<Identifier> stale = List.of();
	private static long modified = -1;
	private static final Map<UUID, Boolean> outside = new ConcurrentHashMap<>();

	private static Method add;
	private static Method remove;
	private static Method list;
	private static boolean looked;

	private OuterWilds() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Borders.WILDS_FILE);
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (modified < 0 || tick % RELOAD_EVERY == 0) reload(server);
		if (tick % EVERY != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player);
	}

	private static void reload(MinecraftServer server) {
		Path file = file();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file);
		} catch (java.io.IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		Identifier before = effect();
		wilds = Borders.readWilds(file);
		Identifier after = effect();
		stale = wilds.previousIds.stream().map(Identifier::tryParse).filter(id -> id != null && !id.equals(after)).distinct().toList();
		// A changed id: nobody keeps the old effect.
		if (!first && before != null && !before.equals(after)) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) postEffect(player, before, false);
		}
	}

	/** The configured post effect, also while it's switched off (so it can be taken away). */
	private static Identifier effect() {
		String id = wilds.postEffectId;
		return id == null || id.isBlank() ? null : Identifier.tryParse(id);
	}

	private static void check(ServerPlayer player) {
		if (player.isRemoved()) return;
		Borders.Border b = HardBorder.borders().get(player.level().dimension().identifier().toString());
		boolean out = b != null && !b.inside(player.getX(), player.getZ(), b.soft);
		Boolean before = outside.put(player.getUUID(), out);
		Borders.Wilds w = wilds;
		Identifier fx = effect();
		if (fx != null) postEffect(player, fx, out && w.enabled && w.postEffect);
		// Only right after joining: earlier ids are saved with the player, nothing adds them again.
		if (before == null) for (Identifier old : stale) postEffect(player, old, false);
		if (before == null || before == out || !w.enabled) return;
		String text = out ? w.enteredText : w.exitedText;
		if (!text.isBlank()) player.sendOverlayMessage(Component.literal(text).withColor(Claims.parseColor(out ? w.enteredColor : w.exitedColor)));
		if (w.sound) Plop.play(player);
	}

	/** Adds or removes the post effect when it isn't already so. */
	private static void postEffect(ServerPlayer player, Identifier id, boolean on) {
		if (!looked) {
			looked = true;
			try {
				add = ServerPlayer.class.getMethod("addPostEffect", Identifier.class);
				remove = ServerPlayer.class.getMethod("removePostEffect", Identifier.class);
				list = ServerPlayer.class.getMethod("getPostEffects");
			} catch (NoSuchMethodException e) {
				NiceControlCenter.LOGGER.info("Outer Wilds: post effects need Minecraft 26.3 or newer; the outer wilds message works without");
			}
		}
		if (list == null) return;
		try {
			boolean has = ((List<?>) list.invoke(player)).contains(id);
			if (on && !has) add.invoke(player, id);
			else if (!on && has) remove.invoke(player, id);
		} catch (ReflectiveOperationException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Outer Wilds: couldn't set post effect {}: {}", id, e.toString());
			list = null;
		}
	}

	public static void forget(UUID player) {
		outside.remove(player);
	}
}
