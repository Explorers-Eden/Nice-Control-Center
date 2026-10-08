package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.execution.CommandQueueEntry;
import net.minecraft.commands.execution.ExecutionContext;

import eu.explorerseden.nicecontrolcenter.core.FunctionFrames;
import eu.explorerseden.nicecontrolcenter.core.FunctionStat;
import eu.explorerseden.nicecontrolcenter.core.Tracker;

/**
 * Times every queued command step and charges it to the function and command line it belongs to.
 *
 * <p>Plain HEAD/RETURN injections instead of a wrapper, because busy data packs run tens of
 * thousands of these per tick. If a step throws, the frame is left open; the tracker resets its
 * stack at the start of every tick.
 */
@Mixin(CommandQueueEntry.class)
public abstract class CommandQueueEntryMixin {
	@Inject(method = "execute", at = @At("HEAD"))
	private void nicecontrolcenter$start(ExecutionContext<?> context, CallbackInfo ci) {
		if (Tracker.isActive()) {
			Tracker.enter();
		}
	}

	@Inject(method = "execute", at = @At("RETURN"))
	private void nicecontrolcenter$end(ExecutionContext<?> context, CallbackInfo ci) {
		if (!Tracker.isActive()) {
			return;
		}
		FunctionFrames frames = ((FunctionFrames.Holder) context).nicecontrolcenter$frames();
		int depth = ((CommandQueueEntry<?>) (Object) this).frame().depth();
		FunctionStat stat = frames.stat(depth, Tracker.bucket);
		Tracker.exit(stat);
		FunctionStat.LineStat line = frames.lineStat(depth, stat);
		line.selfNs += Tracker.lastSelfNs;
		line.entries++;
	}
}
