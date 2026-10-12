package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;

/** Pistons can't push or pull blocks into (or break blocks in) someone else's claim. */
@Mixin(PistonStructureResolver.class)
public abstract class ClaimPistonMixin {
	@Shadow
	@Final
	private Level level;
	@Shadow
	@Final
	private BlockPos pistonPos;
	@Shadow
	@Final
	private Direction pushDirection;
	@Shadow
	@Final
	private List<BlockPos> toPush;
	@Shadow
	@Final
	private List<BlockPos> toDestroy;

	@Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
	private void nicecontrolcenter$claims(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || !ClaimStore.enabled() || level.isClientSide()) return;
		for (BlockPos p : toPush) {
			if (ClaimProtection.crossingBlocked(level, pistonPos, p) || ClaimProtection.crossingBlocked(level, pistonPos, p.relative(pushDirection))) {
				cir.setReturnValue(false);
				return;
			}
		}
		for (BlockPos p : toDestroy) {
			if (ClaimProtection.crossingBlocked(level, pistonPos, p)) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
