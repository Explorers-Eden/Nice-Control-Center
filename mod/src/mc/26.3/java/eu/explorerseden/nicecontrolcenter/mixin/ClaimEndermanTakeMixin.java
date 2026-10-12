package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Endermen don't take blocks out of claims unless the claim allows mob griefing. */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class ClaimEndermanTakeMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private boolean nicecontrolcenter$claims(Level level, BlockPos pos, boolean moved, Operation<Boolean> original) {
		if (!ClaimProtection.mobGriefingAllowed(level, pos)) return false;
		return original.call(level, pos, moved);
	}
}
