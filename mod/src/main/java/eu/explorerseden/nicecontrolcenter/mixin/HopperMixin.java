package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** A hopper that found nothing to move waits a few ticks before looking again (vanilla looks every tick). */
@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin {
	@WrapOperation(method = "pushItemsTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z"))
	private static boolean nicecontrolcenter$idle(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, BooleanSupplier action,
			Operation<Boolean> original) {
		boolean moved = original.call(level, pos, state, hopper, action);
		int idle = PerfControl.hopperIdle();
		// Locked hoppers stay as vanilla, so redstone that unlocks them keeps its timing.
		// 0-3 ticks more by position, so a row of idle hoppers doesn't look again in the same tick.
		if (!moved && idle > 0 && state.getValue(HopperBlock.ENABLED)) {
			((HopperAccessor) hopper).nicecontrolcenter$setCooldownTime(idle + Math.floorMod(pos.getX() + pos.getZ() * 3 + pos.getY() * 7, 4));
		}
		return moved;
	}
}
