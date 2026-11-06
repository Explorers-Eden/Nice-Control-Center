package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * The Katters Structures bosses (by Katter, Explorer's Eden) for wave arenas and dungeons, written in like a mob:
 * {@code katters:arachne}, {@code katters:raj}, {@code katters:rusta}, {@code katters:tenku}, {@code katters:theron}.
 * Built into the mod (no datapack functions needed); they drop nothing.
 */
public final class KatterBosses {
	public static final List<String> KEYS = List.of("arachne", "raj", "rusta", "tenku", "theron");

	private static final List<BossFight> fights = new ArrayList<>();
	private static final Map<UUID, BossFight> owner = new HashMap<>();

	private KatterBosses() {
	}

	/** A Katters boss line ("katters:theron", any case). */
	public static boolean is(String id) {
		return key(id) != null;
	}

	private static String key(String id) {
		if (id == null) return null;
		String s = id.strip().toLowerCase(Locale.ROOT);
		if (!s.startsWith("katters:")) return null;
		String k = s.substring(8);
		return KEYS.contains(k) ? k : null;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (BossFight f : List.copyOf(fights)) {
				if (f.over) {
					drop(f);
					continue;
				}
				try {
					if (!f.alive()) {
						f.finish();
						drop(f);
						continue;
					}
					f.step();
				} catch (RuntimeException e) {
					NiceControlCenter.LOGGER.warn("Games: boss {} stopped after an error: {}", f.name(), e.toString());
					f.finish();
					drop(f);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (BossFight f : List.copyOf(fights)) f.finish();
			fights.clear();
			owner.clear();
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			BossFight f = owner.get(entity.getUUID());
			if (f != null && !f.over) f.hurt(entity, source);
			if (entity instanceof ServerPlayer p && source.getEntity() != null) {
				BossFight by = owner.get(source.getEntity().getUUID());
				if (by != null && !by.over) by.hit(p, source.getEntity());
			}
		});
		// Nothing drops: what fell this tick next to a dying piece goes.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!owner.containsKey(entity.getUUID()) || !(entity.level() instanceof ServerLevel level)) return;
			for (Entity e : level.getEntitiesOfClass(Entity.class, entity.getBoundingBox().inflate(3), x -> (x instanceof ItemEntity || x instanceof ExperienceOrb) && x.tickCount <= 1)) e.discard();
		});
	}

	static void own(Entity e, BossFight f) {
		owner.put(e.getUUID(), f);
	}

	private static void drop(BossFight f) {
		fights.remove(f);
		owner.values().removeIf(x -> x == f);
	}

	/**
	 * The boss at the spot. {@code tags} go on everything it makes (the game's tag), {@code bossTag} on its main bodies
	 * (null: none); {@code bar}: its own boss bar for players close by. Null when it's no Katters boss or the dimension
	 * is gone.
	 */
	public static BossFight spawn(MinecraftServer server, String id, String dim, double x, double y, double z, List<String> tags, String bossTag, boolean bar) {
		return spawn(server, id, dim, x, y, z, tags, bossTag, bar, null);
	}

	/** The same, its bar only for {@code viewers} (the game's players; null: anyone within 48 blocks). */
	public static BossFight spawn(MinecraftServer server, String id, String dim, double x, double y, double z, List<String> tags, String bossTag, boolean bar,
			java.util.function.Predicate<ServerPlayer> viewers) {
		String k = key(id);
		ServerLevel level = eu.explorerseden.nicecontrolcenter.games.GameRuntime.levelOf(server, dim);
		if (k == null || level == null) return null;
		BossFight f = switch (k) {
			case "arachne" -> new ArachneFight(level, tags, bossTag, bar, x, y, z);
			case "raj" -> new RajFight(level, tags, bossTag, bar, x, y, z);
			case "rusta" -> new RustaFight(level, tags, bossTag, bar, x, y, z);
			case "tenku" -> new TenkuFight(level, tags, bossTag, bar, x, y, z);
			default -> new TheronFight(level, tags, bossTag, bar, x, y, z);
		};
		f.viewers = viewers;
		fights.add(f);
		NiceControlCenter.LOGGER.info("Games: boss {} summoned in {} at {} {} {}", f.name(), dim, Math.round(x), Math.round(y), Math.round(z));
		return f;
	}

	/** A Katters boss's name, for messages ("katters:raj" → "Raj Raksha"). */
	public static String title(String id) {
		String k = key(id);
		if (k == null) return id;
		return switch (k) {
			case "arachne" -> "Arachne, Mother of Spiders";
			case "raj" -> "Raj Raksha";
			case "rusta" -> "Rústa, Chieftain of the Ravaged Lands";
			case "tenku" -> "Tenkū Mizu Gami";
			default -> "Magus Theron the Betrayer";
		};
	}
}
