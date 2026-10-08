package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Terrain, surface and carver stages of the default (noise) generator. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@WrapMethod(method = "doFill", require = 0)
	private void nicecontrolcenter$terrain(NoiseChunk noise, ChunkAccess chunk, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(noise, chunk);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(noise, chunk);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_TERRAIN);
		}
	}

	@WrapMethod(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V", require = 0)
	private void nicecontrolcenter$surface(ChunkAccess chunk, NoiseChunk noise, RandomState random, BiomeManager biomes, Set<Holder<Biome>> possible,
			MaterialRule rule, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(chunk, noise, random, biomes, possible, rule);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(chunk, noise, random, biomes, possible, rule);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_SURFACE);
		}
	}

	@WrapMethod(method = "generateCarvers", require = 0)
	private void nicecontrolcenter$carvers(ChunkAccess chunk, Blender blender, NoiseChunk noise, RandomState random, BiomeManager biomes,
			WorldGenRegion region, MaterialRule rule, Operation<Void> original) {
		if (!WorldgenTracker.enabled()) {
			original.call(chunk, blender, noise, random, biomes, region, rule);
			return;
		}
		WorldgenTracker.enter();
		try {
			original.call(chunk, blender, noise, random, biomes, region, rule);
		} finally {
			WorldgenTracker.exitStage(WorldgenTracker.STAGE_CARVERS);
		}
	}
}
