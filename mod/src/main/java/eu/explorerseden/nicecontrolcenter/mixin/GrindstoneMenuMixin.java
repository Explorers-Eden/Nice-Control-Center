package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.tweaks.GrindstoneBooks;

/** Grindstone to books: an enchanted item and a book give an enchanted book (see GrindstoneBooks). */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuMixin {
	@Unique
	private Player nicecontrolcenter$player;

	@Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("TAIL"))
	private void nicecontrolcenter$owner(int id, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
		nicecontrolcenter$player = inventory.player;
	}

	@Inject(method = "computeResult", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$book(ItemStack a, ItemStack b, CallbackInfoReturnable<ItemStack> cir) {
		if (nicecontrolcenter$player == null || nicecontrolcenter$player.level().isClientSide()) return;
		ItemStack book = GrindstoneBooks.result(nicecontrolcenter$player, a, b);
		if (book != null) cir.setReturnValue(book);
	}
}
