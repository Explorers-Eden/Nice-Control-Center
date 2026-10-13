package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.tweaks.GrindstoneBooks;

/** Grindstone to books: the result slot asks for the levels and, when taken, strips the item instead of using it up. */
@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
public abstract class GrindstoneResultMixin extends Slot {
	private GrindstoneResultMixin(Container container, int slot, int x, int y) {
		super(container, slot, x, y);
	}

	@Override
	public boolean mayPickup(Player player) {
		Boolean ours = player.level().isClientSide() ? null : GrindstoneBooks.mayPickup(player);
		return ours != null ? ours : super.mayPickup(player);
	}

	@Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$take(Player player, ItemStack taken, CallbackInfo ci) {
		if (player.level().isClientSide() || !(player.containerMenu instanceof GrindstoneMenu menu)) return;
		if (GrindstoneBooks.take(player, ((GrindstoneMenuAccessor) menu).nicecontrolcenter$repairSlots())) ci.cancel();
	}
}
