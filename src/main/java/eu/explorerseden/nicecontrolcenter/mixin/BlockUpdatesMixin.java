package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Counts scheduled block/fluid ticks and block events (pistons, note blocks) per position, to find redstone clocks. */
@Mixin(ServerLevel.class)
public abstract class BlockUpdatesMixin {
	@Inject(method = "tickBlock", at = @At("HEAD"))
	private void nicecontrolcenter$blockTick(BlockPos pos, Block block, CallbackInfo ci) {
		if (Tracker.isActive()) {
			Tracker.countBlockUpdate((ServerLevel) (Object) this, pos);
		}
	}

	@Inject(method = "tickFluid", at = @At("HEAD"))
	private void nicecontrolcenter$fluidTick(BlockPos pos, Fluid fluid, CallbackInfo ci) {
		if (Tracker.isActive()) {
			Tracker.countBlockUpdate((ServerLevel) (Object) this, pos);
		}
	}

	@Inject(method = "doBlockEvent", at = @At("HEAD"))
	private void nicecontrolcenter$blockEvent(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
		if (Tracker.isActive()) {
			Tracker.countBlockUpdate((ServerLevel) (Object) this, event.pos());
		}
	}
}
