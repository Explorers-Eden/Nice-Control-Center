package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Time per block entity type; this wrapper holds the actual block entity, so no string lookups are needed. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@WrapMethod(method = "tick")
	private void nicecontrolcenter$tick(Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call();
			return;
		}
		Tracker.enter();
		try {
			original.call();
		} finally {
			Tracker.exit(Tracker.bucket.blockEntity(blockEntity.getType()));
			if (blockEntity.getLevel() instanceof ServerLevel level) {
				BlockPos pos = blockEntity.getBlockPos();
				Tracker.bucket.addChunkTime(Tracker.dimension(level), ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), Tracker.lastElapsedNs);
			}
		}
	}
}
