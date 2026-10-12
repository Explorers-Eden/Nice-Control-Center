package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Experience orbs look farther for others to merge with. */
@Mixin(ExperienceOrb.class)
public abstract class XpMergeMixin {
	@WrapOperation(method = "scanForMerges", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"))
	private AABB nicecontrolcenter$radius(AABB box, double r, Operation<AABB> original) {
		return original.call(box, Math.max(r, PerfControl.xpMerge()));
	}
}
