package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.tweaks.AntiXray;

/** A block changed: the hidden ores next to it are sent as they are (players see what they uncover). */
@Mixin(ServerLevel.class)
public abstract class AntiXrayRevealMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void nicecontrolcenter$reveal(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		if (AntiXray.active()) AntiXray.changed((ServerLevel) (Object) this, pos);
	}
}
