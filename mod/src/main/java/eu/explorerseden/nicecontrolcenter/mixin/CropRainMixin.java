package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Crops in the rain: after a crop's random tick (wheat, carrots, potatoes, beetroot, torchflower and pitcher
 * crops, melon and pumpkin stems, sweet berries, cocoa) it may get a second one while rain falls on it.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class CropRainMixin {
	@Unique
	private static boolean nicecontrolcenter$extra;

	@Inject(method = "randomTick", at = @At("TAIL"))
	private void nicecontrolcenter$rain(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (nicecontrolcenter$extra) return;
		Tweaks.Crops c = TweaksControl.settings().crops;
		if (!c.rainGrowth) return;
		var block = ((BlockState) (Object) this).getBlock();
		if (!(block instanceof CropBlock || block instanceof StemBlock || block instanceof SweetBerryBushBlock || block instanceof CocoaBlock)) return;
		if (random.nextInt(100) >= c.rainBoost || !level.isRainingAt(pos.above())) return;
		nicecontrolcenter$extra = true;
		try {
			// The state it has now: the first tick may have grown it already.
			BlockState now = level.getBlockState(pos);
			if (now.getBlock() == block) now.randomTick(level, pos, random);
		} finally {
			nicecontrolcenter$extra = false;
		}
	}
}
