package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.world.entity.Mob;

/** Cube mobs that don't fight in vanilla: none before 26.3 (the sulfur cube came with it). */
public final class CubeMobs {
	private CubeMobs() {
	}

	/** Gives a peaceful cube mob the slimes' attack; false when it's none. */
	public static boolean arm(Mob m) {
		return false;
	}
}
