package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import eu.explorerseden.nicecontrolcenter.core.SmoothSave;
import eu.explorerseden.nicecontrolcenter.core.WarmTargets;

/** Smooth autosave, server side: marks the autosave and spreads the player files over the next ticks; warm teleport targets' upkeep. */
@Mixin(MinecraftServer.class)
public abstract class SmoothSaveServerMixin {
	@Inject(method = "autoSave", at = @At("HEAD"))
	private void nicecontrolcenter$autosaveStart(CallbackInfo ci) {
		SmoothSave.beginAutosave();
	}

	@Inject(method = "autoSave", at = @At("RETURN"))
	private void nicecontrolcenter$autosaveEnd(CallbackInfo ci) {
		SmoothSave.endAutosave();
	}

	@WrapOperation(method = "saveEverything", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;saveAll()V"))
	private void nicecontrolcenter$players(PlayerList players, Operation<Void> original) {
		if (SmoothSave.autosaving()) {
			SmoothSave.queuePlayers((MinecraftServer) (Object) this);
			return;
		}
		SmoothSave.clearPlayers();
		original.call(players);
	}

	@Inject(method = "tickChildren", at = @At("TAIL"))
	private void nicecontrolcenter$savePlayer(BooleanSupplier haveTime, CallbackInfo ci) {
		SmoothSave.tick((MinecraftServer) (Object) this);
		WarmTargets.tick((MinecraftServer) (Object) this);
	}
}
