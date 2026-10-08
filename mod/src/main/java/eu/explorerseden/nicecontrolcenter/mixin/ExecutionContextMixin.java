package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.commands.execution.ExecutionContext;

import eu.explorerseden.nicecontrolcenter.core.FunctionFrames;

@Mixin(ExecutionContext.class)
public abstract class ExecutionContextMixin implements FunctionFrames.Holder {
	@Unique
	private FunctionFrames nicecontrolcenter$frames;

	@Override
	public FunctionFrames nicecontrolcenter$frames() {
		if (nicecontrolcenter$frames == null) {
			nicecontrolcenter$frames = new FunctionFrames();
		}
		return nicecontrolcenter$frames;
	}
}
