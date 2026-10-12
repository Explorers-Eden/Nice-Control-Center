package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Zombies (on hard) don't break doors in claims unless mob griefing is allowed there. */
@Mixin(BreakDoorGoal.class)
public abstract class ClaimBreakDoorMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private boolean nicecontrolcenter$claims(Level level, BlockPos pos, boolean moved, Operation<Boolean> original) {
		if (!ClaimProtection.mobGriefingAllowed(level, pos)) return false;
		return original.call(level, pos, moved);
	}
}
