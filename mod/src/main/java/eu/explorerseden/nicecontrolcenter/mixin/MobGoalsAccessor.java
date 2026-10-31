package eu.explorerseden.nicecontrolcenter.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For games: peaceful and neutral mobs in wave arenas and dungeons get goals to attack players (Aggro). */
@Mixin(Mob.class)
public interface MobGoalsAccessor {
	@Accessor("goalSelector")
	GoalSelector nicecontrolcenter$goals();

	@Accessor("targetSelector")
	GoalSelector nicecontrolcenter$targets();
}
