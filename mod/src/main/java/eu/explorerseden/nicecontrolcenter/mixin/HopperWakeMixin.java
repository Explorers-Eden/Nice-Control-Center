package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import eu.explorerseden.nicecontrolcenter.core.IdleHopper;
import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** A container whose contents changed wakes an idle hopper below it (it pulls from here). */
@Mixin(BlockEntity.class)
public abstract class HopperWakeMixin {
	@Shadow
	protected Level level;

	@Shadow
	protected BlockPos worldPosition;

	@Inject(method = "setChanged()V", at = @At("HEAD"))
	private void nicecontrolcenter$wakeBelow(CallbackInfo ci) {
		if (level == null || level.isClientSide() || !((Object) this instanceof Container) || PerfControl.hopperIdle() <= 0) return;
		if (level.getBlockEntity(worldPosition.below()) instanceof IdleHopper hopper) hopper.nicecontrolcenter$wake();
	}
}
