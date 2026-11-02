package eu.explorerseden.nicecontrolcenter.games;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;

/** What lies around in a game area from earlier rounds: dropped items, XP, arrows and tridents stuck in the ground. */
final class Cleanup {
	private Cleanup() {
	}

	/** A game starts with full health and a full hunger bar (and no fire or missing air). */
	static void refresh(net.minecraft.server.level.ServerPlayer p) {
		if (!p.isAlive()) return;
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.clearFire();
		p.setAirSupply(p.getMaxAirSupply());
	}

	/** Gone from the box; how many. */
	static int items(ServerLevel level, AABB box) {
		if (level == null || box == null) return 0;
		int n = 0;
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof AbstractArrow)) {
			e.discard();
			n++;
		}
		return n;
	}
}
