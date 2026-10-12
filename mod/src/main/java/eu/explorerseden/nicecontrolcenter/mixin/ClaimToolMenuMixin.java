package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.claims.ClaimMode;

/** Claim tools put in a chest (or any other menu slot outside the player's inventory) are gone when it closes. */
@Mixin(AbstractContainerMenu.class)
public abstract class ClaimToolMenuMixin {
	@Inject(method = "removed", at = @At("HEAD"))
	private void nicecontrolcenter$takeTools(Player player, CallbackInfo ci) {
		if (player.level().isClientSide()) return;
		for (Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
			if (!(slot.container instanceof Inventory) && ClaimMode.isTool(slot.getItem())) slot.set(ItemStack.EMPTY);
		}
	}
}
