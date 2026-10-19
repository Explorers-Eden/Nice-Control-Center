package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import eu.explorerseden.nicecontrolcenter.core.WarmTargets;

/** Counts where players teleport to (/tp, also from functions), for warm teleport targets. */
@Mixin(ServerPlayer.class)
public abstract class TeleportWarmMixin {
	@Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("HEAD"))
	private void nicecontrolcenter$teleporting(ServerLevel level, double x, double y, double z, Set<Relative> relative, float yRot, float xRot,
			boolean resetCamera, CallbackInfoReturnable<Boolean> cir) {
		if (relative.contains(Relative.X) || relative.contains(Relative.Z)) return;
		WarmTargets.teleporting((ServerPlayer) (Object) this, level, x, z);
	}
}
