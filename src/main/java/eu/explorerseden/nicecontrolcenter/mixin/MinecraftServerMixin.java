package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.players.PlayerList;

import eu.explorerseden.nicecontrolcenter.core.Phase;
import eu.explorerseden.nicecontrolcenter.core.Tracker;

/** Tick boundaries and the server-wide phases (functions, network, players, autosave). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@WrapMethod(method = "tickServer")
	private void nicecontrolcenter$tick(BooleanSupplier haveTime, Operation<Void> original) {
		if (!Tracker.isServerThread() || !Tracker.prepareTick()) {
			original.call(haveTime);
			return;
		}
		Tracker.beginTick();
		try {
			original.call(haveTime);
		} finally {
			Tracker.endTick();
		}
	}

	@WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ServerFunctionManager;tick()V"))
	private void nicecontrolcenter$functions(ServerFunctionManager functions, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(functions);
			return;
		}
		Tracker.enterPhase(Tracker.serverPhase(Phase.FUNCTIONS));
		try {
			original.call(functions);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = {"tickChildren", "tickServer"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
	private void nicecontrolcenter$connection(MinecraftServer server, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(server);
			return;
		}
		Tracker.enterPhase(Tracker.serverPhase(Phase.CONNECTION));
		try {
			original.call(server);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;tick()V"))
	private void nicecontrolcenter$players(PlayerList players, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(players);
			return;
		}
		Tracker.enterPhase(Tracker.serverPhase(Phase.PLAYERS));
		try {
			original.call(players);
		} finally {
			Tracker.exitPhase();
		}
	}

	@WrapOperation(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;autoSave()V"))
	private void nicecontrolcenter$autosave(MinecraftServer server, Operation<Void> original) {
		if (!Tracker.isActive()) {
			original.call(server);
			return;
		}
		Tracker.enterPhase(Tracker.serverPhase(Phase.AUTOSAVE));
		try {
			original.call(server);
		} finally {
			Tracker.exitPhase();
		}
	}
}
