package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.core.IdleHopper;
import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * A hopper that found nothing to move waits a few ticks before looking again (vanilla looks every tick).
 * A change in the container above ends the wait (HopperWakeMixin), so a filter hopper under a hopper line
 * still catches every item passing through.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin implements IdleHopper {
	@Shadow
	private int cooldownTime;

	@Unique
	private boolean nicecontrolcenter$idle;

	@Override
	public void nicecontrolcenter$wake() {
		if (nicecontrolcenter$idle) {
			nicecontrolcenter$idle = false;
			cooldownTime = 0;
		}
	}

	@WrapOperation(method = "pushItemsTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z"))
	private static boolean nicecontrolcenter$idle(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, BooleanSupplier action,
			Operation<Boolean> original) {
		boolean moved = original.call(level, pos, state, hopper, action);
		int idle = PerfControl.hopperIdle();
		HopperMixin self = (HopperMixin) (Object) hopper;
		self.nicecontrolcenter$idle = false;
		// Locked hoppers stay as vanilla, so redstone that unlocks them keeps its timing.
		// 0-3 ticks more by position, so a row of idle hoppers doesn't look again in the same tick.
		if (!moved && idle > 0 && state.getValue(HopperBlock.ENABLED)) {
			self.cooldownTime = idle + Math.floorMod(pos.getX() + pos.getZ() * 3 + pos.getY() * 7, 4);
			self.nicecontrolcenter$idle = true;
		}
		return moved;
	}
}
