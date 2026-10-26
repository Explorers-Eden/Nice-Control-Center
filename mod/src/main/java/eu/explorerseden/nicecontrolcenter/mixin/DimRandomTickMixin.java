package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Per-dimension tuning: no random ticks in a dimension that has them off (or is frozen while empty). */
@Mixin(ServerLevel.class)
public abstract class DimRandomTickMixin {
	@ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int nicecontrolcenter$randomTicks(int speed, LevelChunk chunk) {
		return speed == 0 ? 0 : PerfControl.randomTicks((ServerLevel) (Object) this, speed);
	}
}
