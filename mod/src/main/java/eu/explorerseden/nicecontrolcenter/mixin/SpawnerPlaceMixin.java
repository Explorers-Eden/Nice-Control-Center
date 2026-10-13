package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

import eu.explorerseden.nicecontrolcenter.tweaks.Spawners;

/** Silk touch spawners: a spawner picked up with Silk Touch keeps its mob when a normal player places it again. */
@Mixin(BlockItem.class)
public abstract class SpawnerPlaceMixin {
	@WrapOperation(method = "updateCustomBlockEntityTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntityType;onlyOpCanSetNbt()Z"))
	private static boolean nicecontrolcenter$ownSpawners(BlockEntityType<?> type, Operation<Boolean> original, @Local(argsOnly = true) ItemStack stack) {
		if (Spawners.marked(stack)) return false;
		return original.call(type);
	}
}
