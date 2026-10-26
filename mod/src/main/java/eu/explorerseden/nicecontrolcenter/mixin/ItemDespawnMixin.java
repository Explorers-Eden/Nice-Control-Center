package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/** Junk items (cobblestone, netherrack…) go after the set time instead of 5 minutes. */
@Mixin(ItemEntity.class)
public abstract class ItemDespawnMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void nicecontrolcenter$junk(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.isRemoved() || !(self.level() instanceof ServerLevel)) return;
		int ticks = PerfControl.junkTicks(self.getItem());
		if (ticks > 0 && self.getAge() >= ticks) {
			self.discard();
			return;
		}
		// A dimension with its own item despawn time (per-dimension tuning).
		int dim = PerfControl.itemDespawnTicks((ServerLevel) self.level());
		if (dim > 0 && self.getAge() >= dim) self.discard();
	}
}
