package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.tweaks.SleepPool;

/** Shared night across the chosen worlds; see SleepPool. */
@Mixin(ServerLevel.class)
public abstract class SleepPoolMixin implements SleepPool.Access {
	@Shadow
	private void wakeUpAllPlayers() {
	}

	@ModifyArg(method = "updateSleepingPlayerList", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/SleepStatus;update(Ljava/util/List;)Z"))
	private List<ServerPlayer> nicecontrolcenter$countShared(List<ServerPlayer> players) {
		return SleepPool.players((ServerLevel) (Object) this, players);
	}

	@ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/SleepStatus;areEnoughDeepSleeping(ILjava/util/List;)Z"), index = 1)
	private List<ServerPlayer> nicecontrolcenter$deepShared(List<ServerPlayer> players) {
		return SleepPool.players((ServerLevel) (Object) this, players);
	}

	@Inject(method = "updateSleepingPlayerList", at = @At("TAIL"))
	private void nicecontrolcenter$recountOthers(CallbackInfo ci) {
		SleepPool.updated((ServerLevel) (Object) this);
	}

	@Inject(method = "wakeUpAllPlayers", at = @At("TAIL"))
	private void nicecontrolcenter$wakeOthers(CallbackInfo ci) {
		SleepPool.wokeUp((ServerLevel) (Object) this);
	}

	@Override
	public void nicecontrolcenter$wakeUp() {
		wakeUpAllPlayers();
	}
}
