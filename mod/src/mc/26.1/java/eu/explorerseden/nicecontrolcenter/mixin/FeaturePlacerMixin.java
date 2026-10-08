package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/** Time per placed feature (Minecraft 26.1–26.2: every placement goes through PlacedFeature.placeWithContext). */
@Mixin(PlacedFeature.class)
public abstract class FeaturePlacerMixin {
	@WrapMethod(method = "placeWithContext", require = 0)
	private boolean nicecontrolcenter$place(PlacementContext context, RandomSource random, BlockPos pos, Operation<Boolean> original) {
		if (!WorldgenTracker.enabled()) {
			return original.call(context, random, pos);
		}
		WorldgenTracker.enter();
		try {
			return original.call(context, random, pos);
		} finally {
			WorldgenTracker.exitFeature((PlacedFeature) (Object) this, context.getLevel().registryAccess());
		}
	}
}
