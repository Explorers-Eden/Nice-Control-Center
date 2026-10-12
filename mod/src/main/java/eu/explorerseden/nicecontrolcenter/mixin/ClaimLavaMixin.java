package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Lava doesn't light fires in claims that don't allow fire. */
@Mixin(LavaFluid.class)
public abstract class ClaimLavaMixin {
	@WrapOperation(method = "randomTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean nicecontrolcenter$claims(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		if (!ClaimProtection.fireAllowed(level, pos)) return false;
		return original.call(level, pos, state);
	}
}
