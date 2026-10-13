package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.tweaks.Saplings;

/** Saplings lying on the ground plant themselves after a while (see Saplings). */
@Mixin(ItemEntity.class)
public abstract class SaplingReplantMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void nicecontrolcenter$replant(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!self.isRemoved()) Saplings.tick(self);
	}
}
