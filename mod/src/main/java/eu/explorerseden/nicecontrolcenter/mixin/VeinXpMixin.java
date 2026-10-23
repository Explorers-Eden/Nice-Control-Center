package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import eu.explorerseden.nicecontrolcenter.tweaks.Felling;

/** Vein mining with "Also drop XP" off: the vein's other blocks drop no XP orbs. */
@Mixin(Block.class)
public abstract class VeinXpMixin {
	@Inject(method = "popExperience", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noVeinXp(ServerLevel level, BlockPos pos, int amount, CallbackInfo ci) {
		if (Felling.noXp()) ci.cancel();
	}
}
