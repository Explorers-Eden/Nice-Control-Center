package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;

import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/** AFK players who aren't in bed don't count when the game works out how many need to sleep to skip the night. */
@Mixin(SleepStatus.class)
public abstract class SleepAfkMixin {
	@ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> nicecontrolcenter$withoutAfk(List<ServerPlayer> players) {
		if (!TweaksControl.settings().sleep.ignoreAfk) return players;
		return players.stream().filter(p -> p.isSleeping() || !PlayerActivity.isAfk(p)).toList();
	}
}
