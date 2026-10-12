package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Performance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lag cleanup on demand: dropped items and XP orbs, and mobs beyond a per-chunk cap. A preview counts
 * what would go; nothing named, tamed, leashed, riding or marked persistent is ever removed. Runs on the
 * server thread.
 */
public final class Cleanup {
	private Cleanup() {
	}

	/** Dropped items and XP per dimension; removes them when run is set. */
	public static Map<String, Object> items(MinecraftServer server, boolean run) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> dims = new ArrayList<>();
		int items = 0;
		int orbs = 0;
		for (ServerLevel level : server.getAllLevels()) {
			int i = 0;
			int o = 0;
			List<Entity> doomed = new ArrayList<>();
			for (Entity e : level.getAllEntities()) {
				if (e instanceof ItemEntity) {
					i++;
					doomed.add(e);
				} else if (e instanceof ExperienceOrb) {
					o++;
					doomed.add(e);
				}
			}
			if (run) doomed.forEach(Entity::discard);
			if (i + o > 0) dims.add(Map.of("dimension", level.dimension().identifier().toString(), "items", i, "xp", o));
			items += i;
			orbs += o;
		}
		out.put("items", items);
		out.put("xp", orbs);
		out.put("dimensions", dims);
		if (run) NiceControlCenter.LOGGER.info("Cleanup: removed {} dropped items and {} XP orbs", items, orbs);
		return out;
	}

	/** Mobs that may go: not named, tamed, leashed, riding, carrying or persistent. */
	private static boolean removable(Entity e) {
		if (!(e instanceof Mob mob)) return false;
		if (mob.hasCustomName() || mob.isPersistenceRequired() || mob.isPassenger() || mob.isVehicle()) return false;
		if (mob instanceof Leashable l && l.isLeashed()) return false;
		return !(mob instanceof OwnableEntity owned && owned.getOwnerReference() != null);
	}

	private record Key(ChunkPos chunk, EntityType<?> type) {
	}

	/** The mob limits, looked up by id, then group, then *. */
	public static final class Limits {
		private final Map<String, Integer> ids = new HashMap<>();
		private final Map<String, Integer> groups = new HashMap<>();
		private int any = -1;

		public Limits(List<Performance.MobRule> rules) {
			for (Performance.MobRule r : rules) {
				if (r.type.equals("*")) any = r.perChunk;
				else if (r.type.startsWith("#")) groups.put(r.type, r.perChunk);
				else ids.put(r.type, r.perChunk);
			}
		}

		public boolean isEmpty() {
			return ids.isEmpty() && groups.isEmpty() && any < 0;
		}

		/** The limit for this kind of mob, or -1 when no rule names it. */
		public int of(EntityType<?> type, Mob example) {
			Integer byId = ids.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
			if (byId != null) return byId;
			Integer byGroup = groups.get(group(type, example));
			if (byGroup != null) return byGroup;
			return any;
		}

		private static String group(EntityType<?> type, Mob example) {
			if (example instanceof AbstractVillager) return "#villagers";
			MobCategory category = type.getCategory();
			return switch (category) {
				case MONSTER -> "#monsters";
				case CREATURE -> "#animals";
				case AMBIENT -> "#ambient";
				case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> "#water";
				default -> "";
			};
		}
	}

	/** Old style: one limit for every kind (schedules saved before mob limits existed). */
	public static Map<String, Object> excess(MinecraftServer server, int perChunk, boolean run) {
		return excess(server, new Limits(List.of(new Performance.MobRule("*", perChunk))), run);
	}

	/** Mobs over their limit per chunk (see {@link Limits}); removes the excess when run is set. */
	public static Map<String, Object> excess(MinecraftServer server, Limits limits, boolean run) {
		List<Map<String, Object>> chunks = new ArrayList<>();
		int total = 0;
		for (ServerLevel level : server.getAllLevels()) {
			Map<Key, List<Entity>> groups = new HashMap<>();
			for (Entity e : level.getAllEntities()) {
				if (removable(e)) groups.computeIfAbsent(new Key(e.chunkPosition(), e.getType()), k -> new ArrayList<>()).add(e);
			}
			String dim = level.dimension().identifier().toString();
			for (Map.Entry<Key, List<Entity>> g : groups.entrySet()) {
				List<Entity> list = g.getValue();
				int limit = limits.of(g.getKey().type(), (Mob) list.get(0));
				if (limit < 0 || list.size() <= limit) continue;
				int extra = list.size() - limit;
				total += extra;
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("dimension", dim);
				row.put("x", g.getKey().chunk().x());
				row.put("z", g.getKey().chunk().z());
				row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(g.getKey().type()).toString());
				row.put("count", list.size());
				row.put("limit", limit);
				row.put("remove", extra);
				chunks.add(row);
				if (run) list.subList(limit, list.size()).forEach(Entity::discard);
			}
		}
		chunks.sort(Comparator.comparingInt((Map<String, Object> r) -> (int) r.get("remove")).reversed());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("remove", total);
		out.put("chunks", chunks.size() > 50 ? chunks.subList(0, 50) : chunks);
		out.put("chunkCount", chunks.size());
		if (run && total > 0) NiceControlCenter.LOGGER.info("Cleanup: removed {} mobs over their limit per chunk", total);
		return out;
	}

	/** Dropped items over perChunk in a chunk, oldest first; items younger than 10 seconds always stay. Returns how many went. */
	public static int itemsOver(MinecraftServer server, int perChunk) {
		int removed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			Map<ChunkPos, List<ItemEntity>> byChunk = new HashMap<>();
			for (Entity e : level.getAllEntities()) {
				if (e instanceof ItemEntity item) byChunk.computeIfAbsent(e.chunkPosition(), k -> new ArrayList<>()).add(item);
			}
			for (List<ItemEntity> list : byChunk.values()) {
				if (list.size() <= perChunk) continue;
				list.sort(Comparator.comparingInt(ItemEntity::getAge).reversed());
				for (int i = 0; i < list.size() - perChunk; i++) {
					if (list.get(i).getAge() < 200) break;
					list.get(i).discard();
					removed++;
				}
			}
		}
		return removed;
	}
}
