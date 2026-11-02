package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * Per-dimension "random looks" (always in the dungeon dimension): each mob there gets random colours and kinds
 * once, for the ones whose looks aren't data-driven (llamas, parrots, horses, rabbits, axolotls, foxes,
 * mooshrooms, salmon, tropical fish, sheep, shulkers) and villagers a random job and biome type. Named mobs are
 * left as they are. Data-driven ones (cows, pigs, cats…) get theirs when games summon them (MobLines).
 */
public final class RandomLooks {
	/** Done once: the mob keeps its looks after that. */
	private static final String DONE = "ncc_looks";
	private static final Random random = new Random();

	private RandomLooks() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
			if (e instanceof Mob m && !m.entityTags().contains(DONE) && !m.hasCustomName() && PerfControl.randomLooks(level)) roll(level, m);
		});
	}

	/** Villagers and wandering traders there open no trading screen (VillagerTradeMixin). */
	public static boolean noTrading(Entity e) {
		return PerfControl.randomLooks(e.level());
	}

	private static void roll(ServerLevel level, Mob m) {
		m.addTag(DONE);
		pick(m, DataComponents.LLAMA_VARIANT, net.minecraft.world.entity.animal.equine.Llama.Variant.values());
		pick(m, DataComponents.PARROT_VARIANT, net.minecraft.world.entity.animal.parrot.Parrot.Variant.values());
		pick(m, DataComponents.HORSE_VARIANT, net.minecraft.world.entity.animal.equine.Variant.values());
		// Not the killer bunny.
		pick(m, DataComponents.RABBIT_VARIANT, java.util.Arrays.stream(net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.values()).filter(v -> !v.name().equals("EVIL"))
				.toArray(net.minecraft.world.entity.animal.rabbit.Rabbit.Variant[]::new));
		pick(m, DataComponents.AXOLOTL_VARIANT, net.minecraft.world.entity.animal.axolotl.Axolotl.Variant.values());
		pick(m, DataComponents.FOX_VARIANT, net.minecraft.world.entity.animal.fox.Fox.Variant.values());
		pick(m, DataComponents.MOOSHROOM_VARIANT, net.minecraft.world.entity.animal.cow.MushroomCow.Variant.values());
		pick(m, DataComponents.SALMON_SIZE, net.minecraft.world.entity.animal.fish.Salmon.Variant.values());
		pick(m, DataComponents.TROPICAL_FISH_PATTERN, net.minecraft.world.entity.animal.fish.TropicalFish.Pattern.values());
		pick(m, DataComponents.TROPICAL_FISH_BASE_COLOR, DyeColor.values());
		pick(m, DataComponents.TROPICAL_FISH_PATTERN_COLOR, DyeColor.values());
		pick(m, DataComponents.SHEEP_COLOR, DyeColor.values());
		pick(m, DataComponents.SHULKER_COLOR, DyeColor.values());
		if (m instanceof Villager v && !v.isBaby()) {
			Holder<VillagerProfession> job = any(level, Registries.VILLAGER_PROFESSION, "none", "nitwit");
			Holder<VillagerType> type = any(level, Registries.VILLAGER_TYPE);
			var data = v.getVillagerData();
			if (job != null) data = data.withProfession(job);
			if (type != null) data = data.withType(type);
			v.setVillagerData(data);
			// A little experience, so it keeps the job without a workstation.
			if (v.getVillagerXp() == 0) v.setVillagerXp(1);
		} else if (m instanceof Villager v) {
			Holder<VillagerType> type = any(level, Registries.VILLAGER_TYPE);
			if (type != null) v.setVillagerData(v.getVillagerData().withType(type));
		}
	}

	/** Sets the component only on mobs that have it (setComponent ignores the rest). */
	private static <T> void pick(Mob m, DataComponentType<T> type, T[] values) {
		if (values.length == 0 || m.get(type) == null) return;
		m.setComponent(type, values[random.nextInt(values.length)]);
	}

	private static <T> Holder<T> any(ServerLevel level, ResourceKey<net.minecraft.core.Registry<T>> registry, String... skip) {
		List<Holder<T>> all = new ArrayList<>();
		level.registryAccess().lookupOrThrow(registry).listElements().forEach(h -> {
			String path = h.key().identifier().getPath();
			for (String s : skip) if (path.equals(s)) return;
			all.add(h);
		});
		return all.isEmpty() ? null : all.get(random.nextInt(all.size()));
	}
}
