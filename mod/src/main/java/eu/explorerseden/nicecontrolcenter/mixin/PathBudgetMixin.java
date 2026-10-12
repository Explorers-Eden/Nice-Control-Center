package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** At most so many new path searches per tick; over the budget a mob finds no path this tick and tries again later. */
@Mixin(PathNavigation.class)
public abstract class PathBudgetMixin {
	@Shadow
	@Final
	protected Mob mob;

	@WrapOperation(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
	private Path nicecontrolcenter$budget(PathFinder finder, PathNavigationRegion region, Mob who, Set<BlockPos> targets, float maxLength, int reach,
			float visited, Operation<Path> original) {
		if (!PerfControl.mayPathfind(mob)) return null;
		return original.call(finder, region, who, targets, maxLength, reach, visited);
	}
}
