package eu.explorerseden.nicecontrolcenter.mixin;

import eu.explorerseden.nicecontrolcenter.core.Pregen;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the server awake while pregenerating with nobody online. Only the value the tick loop reads is
 * changed; server.properties stays as it is, even if the server crashes mid-run.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerPauseMixin {
	@Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true, require = 0)
	private void nicecontrolcenter$noPauseWhilePregenerating(CallbackInfoReturnable<Integer> cir) {
		if (Pregen.active()) {
			cir.setReturnValue(0);
		}
	}
}
