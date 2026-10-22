package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * Lighter mob AI for brain mobs: far from players, sensors and behaviours run only every few ticks. Memories are
 * still counted down every tick, so their timers keep vanilla length.
 */
@Mixin(Brain.class)
public abstract class BrainThrottleMixin<E extends LivingEntity> {
	@Shadow
	protected abstract void forgetOutdatedMemories();

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$far(ServerLevel level, E entity, CallbackInfo ci) {
		if (PerfControl.brainThinks(entity)) return;
		forgetOutdatedMemories();
		ci.cancel();
	}
}
