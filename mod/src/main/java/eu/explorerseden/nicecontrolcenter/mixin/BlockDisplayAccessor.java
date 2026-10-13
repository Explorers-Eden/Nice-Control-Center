package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

/** For the falling tree animation. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
	@Invoker("setBlockState")
	void nicecontrolcenter$setBlockState(BlockState state);
}
