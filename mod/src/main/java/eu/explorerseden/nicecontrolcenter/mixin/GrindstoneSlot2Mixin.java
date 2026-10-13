package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.tweaks.GrindstoneBooks;

/** Grindstone to books: a plain book may go into the grindstone's input slots. */
@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$2")
public abstract class GrindstoneSlot2Mixin {
	@Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
	private void nicecontrolcenter$book(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && GrindstoneBooks.isBook(stack)) cir.setReturnValue(true);
	}
}
