package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Far from players, a mob re-plans its goals only every few ticks; what it's doing keeps running (Pufferfish's goal throttle). */
@Mixin(Mob.class)
public abstract class MobAiMixin {
	@WrapOperation(method = "serverAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
	private void nicecontrolcenter$goals(GoalSelector selector, Operation<Void> original) {
		if (PerfControl.plansGoals((Mob) (Object) this)) original.call(selector);
		else selector.tickRunningGoals(false);
	}
}
