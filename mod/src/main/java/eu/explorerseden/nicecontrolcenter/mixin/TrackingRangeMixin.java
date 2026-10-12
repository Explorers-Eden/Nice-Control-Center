package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** While the server lags, players see mobs from less far (a step of the lag protection); other players always as usual. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackingRangeMixin {
	@Shadow
	@Final
	private Entity entity;

	@ModifyReturnValue(method = "getEffectiveRange", at = @At("RETURN"))
	private int nicecontrolcenter$range(int range) {
		int percent = PerfControl.trackingPercent();
		if (percent >= 100 || entity instanceof Player) return range;
		return Math.max(16, range * percent / 100);
	}
}
