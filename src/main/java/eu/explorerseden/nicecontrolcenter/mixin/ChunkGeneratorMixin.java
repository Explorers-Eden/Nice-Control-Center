package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** World generation stages shared by all generators: structure planning and decoration (features + structures). */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
	@WrapMethod(method = "applyBiomeDecoration", require = 0)
	private void nicecontrolcenter$decorate(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(level, chunk, structures);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(level, chunk, structures);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_DECORATION);
			WorldgenTracker.newChunk(level.getLevel().dimension().identifier().toString(), chunk.getPos().x(), chunk.getPos().z());
		}
	}

	@WrapMethod(method = "createStructures", require = 0)
	private void nicecontrolcenter$structures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
			StructureTemplateManager templates, ResourceKey<Level> dimension, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(registries, state, structures, chunk, templates, dimension);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(registries, state, structures, chunk, templates, dimension);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_STRUCTURE_PLANNING);
		}
	}
}
