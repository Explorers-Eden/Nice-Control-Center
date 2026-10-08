package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Time per structure while its pieces are placed into a chunk. */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
	@Shadow
	public abstract Structure getStructure();

	@WrapMethod(method = "placeInChunk", require = 0)
	private void nicecontrolcenter$place(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
			BoundingBox box, ChunkPos chunk, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(level, structures, generator, random, box, chunk);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(level, structures, generator, random, box, chunk);
		} finally {
			WorldgenTracker.exitStructure(getStructure(), level.registryAccess());
		}
	}
}
