package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.math.Transformation;

import net.minecraft.world.entity.Display;

/** For the falling tree animation. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setTransformation")
	void nicecontrolcenter$setTransformation(Transformation transformation);

	@Invoker("setTransformationInterpolationDuration")
	void nicecontrolcenter$setInterpolationDuration(int ticks);

	@Invoker("setTransformationInterpolationDelay")
	void nicecontrolcenter$setInterpolationDelay(int ticks);
}
