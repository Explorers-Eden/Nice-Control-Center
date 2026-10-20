package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

/** For pets' name tags and speech bubbles. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void nicecontrolcenter$setText(Component text);

	@Invoker("setLineWidth")
	void nicecontrolcenter$setLineWidth(int width);

	@Invoker("setBackgroundColor")
	void nicecontrolcenter$setBackgroundColor(int argb);
}
