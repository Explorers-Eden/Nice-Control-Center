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

	/**
	 * A game starts with full health (no fire or missing air) and the hunger bar at 17 without saturation: enough to
	 * sprint, one short of what heals by itself (18), so health only comes back by eating, not as if they couldn't die.
	 */
	static void refresh(net.minecraft.server.level.ServerPlayer p) {
		if (!p.isAlive()) return;
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(17);
		p.getFoodData().setSaturation(0f);
		p.clearFire();
		p.setAirSupply(p.getMaxAirSupply());
	}

	/**
	 * What the game's mobs left behind without the game's tag (an evoker's vexes, split slimes, zombie reinforcements,
	 * potion clouds, fireballs, primed TNT…): monsters with AI, projectiles and the like in the box go. NPCs and
	 * decoration (mobs without AI) stay.
	 */
	static int leftovers(ServerLevel level, AABB box) {
		if (level == null || box == null) return 0;
		int n = 0;
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, Cleanup::leftover)) {
			e.discard();
			n++;
		}
		return n;
	}

	private static boolean leftover(Entity e) {
		if (e instanceof net.minecraft.world.entity.player.Player) return false;
		if (e.entityTags().stream().anyMatch(t -> t.startsWith("ncc_npc"))) return false;
		if (e instanceof net.minecraft.world.entity.Mob m) return m instanceof net.minecraft.world.entity.monster.Enemy && !m.isNoAi();
		return e instanceof net.minecraft.world.entity.projectile.Projectile || e instanceof net.minecraft.world.entity.AreaEffectCloud
				|| e instanceof net.minecraft.world.entity.item.PrimedTnt || e instanceof net.minecraft.world.entity.item.FallingBlockEntity
				|| e instanceof net.minecraft.world.entity.projectile.EvokerFangs;
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
