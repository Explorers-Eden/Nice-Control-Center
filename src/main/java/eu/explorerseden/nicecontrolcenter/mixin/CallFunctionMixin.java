package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.CallFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.resources.Identifier;

import eu.explorerseden.nicecontrolcenter.core.FunctionFrames;
import eu.explorerseden.nicecontrolcenter.core.FunctionStat;
import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Remembers which function owns the new frame depth, so its queued commands can be charged to it. */
@Mixin(CallFunction.class)
public abstract class CallFunctionMixin {
	@Shadow
	@Final
	private InstantiatedFunction<?> function;

	@Inject(method = "execute(Lnet/minecraft/commands/ExecutionCommandSource;Lnet/minecraft/commands/execution/ExecutionContext;Lnet/minecraft/commands/execution/Frame;)V", at = @At("HEAD"))
	private void nicecontrolcenter$call(ExecutionCommandSource<?> source, ExecutionContext<?> context, Frame frame, CallbackInfo ci) {
		if (!Tracker.isActive()) {
			return;
		}
		Identifier id = function.id();
		FunctionFrames frames = ((FunctionFrames.Holder) context).nicecontrolcenter$frames();
		frames.call(frame.depth() + 1, id);
		FunctionStat stat = frames.stat(frame.depth() + 1, Tracker.bucket);
		stat.runs++;
		if (frame.depth() == 0 && stat.calledFrom == null) {
			stat.calledFrom = Tracker.currentPhase();
		}
	}
}
