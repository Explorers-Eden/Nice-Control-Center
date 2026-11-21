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

	/** Lights a TNT sulfur cube's fuse: none here. */
	public static void prime(net.minecraft.world.entity.Entity e) {
	}

	/** A sulfur cube with its fuse lit: none here. */
	public static boolean primed(net.minecraft.world.entity.Entity e) {
		return false;
	}
}
