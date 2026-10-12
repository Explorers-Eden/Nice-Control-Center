package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.claims.ClaimMode;

/** A dropped claim tool never lands in the world (claim mode then ends, see ClaimMode.tick). */
@Mixin(ServerLevel.class)
public abstract class ClaimToolDropMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noToolDrops(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof ItemEntity item && ClaimMode.isTool(item.getItem())) cir.setReturnValue(false);
	}
}
