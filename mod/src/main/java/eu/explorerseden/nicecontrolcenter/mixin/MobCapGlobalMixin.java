package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.MobCategory;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Dynamic distances: the world-wide mob cap scaled down while the server lags. */
@Mixin(targets = "net.minecraft.world.level.NaturalSpawner$SpawnState")
public abstract class MobCapGlobalMixin {
	@WrapOperation(method = "canSpawnForCategoryGlobal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/MobCategory;getMaxInstancesPerChunk()I"))
	private int nicecontrolcenter$cap(MobCategory category, Operation<Integer> original) {
		int cap = original.call(category);
		int pct = PerfControl.mobCap();
		return pct >= 100 ? cap : Math.max(1, cap * pct / 100);
	}
}
