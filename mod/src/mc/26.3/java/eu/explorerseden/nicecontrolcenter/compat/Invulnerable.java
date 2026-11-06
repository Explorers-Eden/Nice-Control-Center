package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.world.entity.Entity;

/** An entity made (or no longer) untouchable (this version: Entity.setPermanentlyInvulnerable). */
public final class Invulnerable {
	private Invulnerable() {
	}

	public static void set(Entity e, boolean on) {
		e.setPermanentlyInvulnerable(on);
	}
}
