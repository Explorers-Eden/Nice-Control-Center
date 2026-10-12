package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Block entities in frozen chunks don't tick. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class PerfBlockEntityMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@WrapMethod(method = "tick")
	private void nicecontrolcenter$frozen(Operation<Void> original) {
		if (blockEntity.getLevel() instanceof ServerLevel level) {
			BlockPos pos = blockEntity.getBlockPos();
			if (PerfControl.frozen(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
		}
		original.call();
	}
}
