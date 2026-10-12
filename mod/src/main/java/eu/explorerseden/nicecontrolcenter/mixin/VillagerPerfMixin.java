package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Villagers: the breeding cap, and trapped villagers (trading cells) think less often. */
@Mixin(Villager.class)
public abstract class VillagerPerfMixin {
	@ModifyReturnValue(method = "canBreed", at = @At("RETURN"))
	private boolean nicecontrolcenter$cap(boolean can) {
		return can && PerfControl.mayBreed((Villager) (Object) this);
	}

	@WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
	private void nicecontrolcenter$brain(Brain<?> brain, ServerLevel level, LivingEntity entity, Operation<Void> original) {
		if (PerfControl.villagerThinks((Villager) (Object) this)) original.call(brain, level, entity);
	}
}
