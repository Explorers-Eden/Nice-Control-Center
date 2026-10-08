package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.BuildContexts;

import eu.explorerseden.nicecontrolcenter.core.FunctionFrames;
import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Marks which command line is running at a frame depth; later queue steps at that depth belong to it. */
@Mixin(BuildContexts.class)
public abstract class BuildContextsMixin {
	@Shadow
	@Final
	private String commandInput;

	@Inject(method = "execute(Lnet/minecraft/commands/ExecutionCommandSource;Ljava/util/List;Lnet/minecraft/commands/execution/ExecutionContext;Lnet/minecraft/commands/execution/Frame;Lnet/minecraft/commands/execution/ChainModifiers;)V", at = @At("HEAD"))
	private void nicecontrolcenter$line(ExecutionCommandSource<?> source, List<?> sources, ExecutionContext<?> context, Frame frame,
			ChainModifiers modifiers, CallbackInfo ci) {
		if (!Tracker.isActive()) {
			return;
		}
		FunctionFrames frames = ((FunctionFrames.Holder) context).nicecontrolcenter$frames();
		int depth = frame.depth();
		frames.line(depth, commandInput);
		if (!((Object) this instanceof BuildContexts.Continuation<?>)) {
			frames.lineStat(depth, frames.stat(depth, Tracker.bucket)).runs++;
		}
	}
}
