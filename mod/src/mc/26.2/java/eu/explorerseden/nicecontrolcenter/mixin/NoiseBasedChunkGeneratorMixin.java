package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Terrain, surface and carver stages of the default (noise) generator (Minecraft 26.1–26.2 signatures). */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@WrapMethod(method = "doFill", require = 0)
	private ChunkAccess nicecontrolcenter$terrain(Blender blender, StructureManager structures, RandomState random, ChunkAccess chunk, int minCell, int cellCount,
			Operation<ChunkAccess> original) {
		if (!WorldgenTracker.enabled()) {
			return original.call(blender, structures, random, chunk, minCell, cellCount);
		}
		WorldgenTracker.enter();
		try {
			return original.call(blender, structures, random, chunk, minCell, cellCount);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_TERRAIN);
		}
	}

	@WrapMethod(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/levelgen/blending/Blender;Ljava/util/Set;)V", require = 0)
	private void nicecontrolcenter$surface(ChunkAccess chunk, WorldGenerationContext context, RandomState random, StructureManager structures, BiomeManager biomes,
			Blender blender, Set<Holder<Biome>> possible, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(chunk, context, random, structures, biomes, blender, possible);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(chunk, context, random, structures, biomes, blender, possible);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_SURFACE);
		}
	}

	@WrapMethod(method = "applyCarvers", require = 0)
	private void nicecontrolcenter$carvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk,
			Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(region, seed, random, biomes, structures, chunk);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(region, seed, random, biomes, structures, chunk);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_CARVERS);
		}
	}
}
