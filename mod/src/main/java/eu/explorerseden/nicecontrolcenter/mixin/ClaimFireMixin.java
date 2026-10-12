package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Fire doesn't burn blocks or spread in claims that don't allow it (fire lit by members stays and burns nothing). */
@Mixin(FireBlock.class)
public abstract class ClaimFireMixin {
	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noBurning(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
		if (!ClaimProtection.fireAllowed(level, pos)) ci.cancel();
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean nicecontrolcenter$noSpreading(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		if (!ClaimProtection.fireAllowed(level, pos)) return false;
		return original.call(level, pos, state);
	}
}
