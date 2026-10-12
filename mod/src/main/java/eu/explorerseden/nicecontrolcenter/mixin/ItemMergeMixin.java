package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Dropped items look farther for others to merge with (Paper's merge radius). */
@Mixin(ItemEntity.class)
public abstract class ItemMergeMixin {
	@WrapOperation(method = "mergeWithNeighbours", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"))
	private AABB nicecontrolcenter$radius(AABB box, double x, double y, double z, Operation<AABB> original) {
		double r = PerfControl.itemMerge();
		return original.call(box, Math.max(x, r), y, Math.max(z, r));
	}
}
