package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Time per placed feature during world generation (every placement, including nested ones, goes through here). */
@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerMixin {
	@Shadow
	@Final
	private WorldGenLevel level;

	@WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z", require = 0)
	private boolean nicecontrolcenter$place(PlacedFeature feature, RandomSource random, BlockPos pos, boolean checkBiome, Operation<Boolean> original) {
		if (!WorldgenTracker.enabled()) {
			return original.call(feature, random, pos, checkBiome);
		}
		WorldgenTracker.enter();
		try {
			return original.call(feature, random, pos, checkBiome);
		} finally {
			WorldgenTracker.exitFeature(feature, level.registryAccess());
		}
	}
}
