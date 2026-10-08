package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;

@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {
	@Accessor("blockTicks")
	LevelTicks<Block> nicecontrolcenter$blockTicks();

	@Accessor("fluidTicks")
	LevelTicks<Fluid> nicecontrolcenter$fluidTicks();
}
