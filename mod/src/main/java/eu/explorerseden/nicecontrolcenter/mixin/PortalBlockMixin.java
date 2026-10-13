package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;

import eu.explorerseden.nicecontrolcenter.tweaks.AnyShapePortal;

/** A portal in any shape stays while its obsidian frame is whole (vanilla would break it as "not a rectangle"). */
@Mixin(NetherPortalBlock.class)
public abstract class PortalBlockMixin {
	@WrapOperation(method = "updateShape", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/portal/PortalShape;isComplete()Z"))
	private boolean nicecontrolcenter$anyShape(PortalShape shape, Operation<Boolean> original, BlockState state, LevelReader level, ScheduledTickAccess ticks,
			BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
		if (original.call(shape)) return true;
		return AnyShapePortal.enabled() && AnyShapePortal.stillClosed(level, pos, state.getValue(NetherPortalBlock.AXIS));
	}
}
