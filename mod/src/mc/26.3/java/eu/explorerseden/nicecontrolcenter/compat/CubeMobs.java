package eu.explorerseden.nicecontrolcenter.compat;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;

import eu.explorerseden.nicecontrolcenter.mixin.MobGoalsAccessor;

/** Cube mobs that don't fight in vanilla (26.3: the sulfur cube). */
public final class CubeMobs {
	private CubeMobs() {
	}

	/** Gives a sulfur cube the slimes' attack (it hops at its target, touching hurts); false when it's none. */
	public static boolean arm(Mob m) {
		if (!(m instanceof SulfurCube cube) || ATTACK == null) return false;
		try {
			((MobGoalsAccessor) m).nicecontrolcenter$goals().addGoal(0, (net.minecraft.world.entity.ai.goal.Goal) ATTACK.newInstance(cube));
			return true;
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/** The slimes' attack goal (protected in AbstractCubeMob). */
	private static final java.lang.reflect.Constructor<?> ATTACK = attack();

	private static java.lang.reflect.Constructor<?> attack() {
		try {
			var c = Class.forName("net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobAttackGoal").getDeclaredConstructor(AbstractCubeMob.class);
			c.setAccessible(true);
			return c;
		} catch (ReflectiveOperationException | RuntimeException e) {
			eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.warn("Games: sulfur cubes can't be made to fight here: {}", e.toString());
			return null;
		}
	}
}
