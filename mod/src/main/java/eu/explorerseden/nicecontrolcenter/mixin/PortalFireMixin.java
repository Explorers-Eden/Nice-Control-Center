package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;

import eu.explorerseden.nicecontrolcenter.tweaks.AnyShapePortal;

/** Fire lights nether portals in any closed obsidian shape, where vanilla only knows rectangles. */
@Mixin(BaseFireBlock.class)
public abstract class PortalFireMixin {
	/** Flint and steel against the side of such a frame may place fire there. */
	@Inject(method = "isPortal", at = @At("RETURN"), cancellable = true)
	private static void nicecontrolcenter$anyShape(Level level, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && !level.isClientSide() && AnyShapePortal.canLight(level, pos)) cir.setReturnValue(true);
	}

	@Inject(method = "onPlace", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$light(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved, CallbackInfo ci) {
		if (level.isClientSide() || oldState.is(state.getBlock()) || !AnyShapePortal.enabled() || !AnyShapePortal.inPortalDimension(level)) return;
		// Vanilla's rectangles stay vanilla's.
		if (PortalShape.findEmptyPortalShape(level, pos, Direction.Axis.X).isPresent()) return;
		if (AnyShapePortal.light(level, pos)) ci.cancel();
	}
}
