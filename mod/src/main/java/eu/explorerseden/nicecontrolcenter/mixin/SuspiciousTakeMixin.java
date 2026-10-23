package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.tweaks.SuspiciousBlocks;

/**
 * Suspicious sand and gravel crafting: taking the result uses up the sand and the item. Without a recipe vanilla
 * would hand every ingredient back as a leftover.
 */
@Mixin(ResultSlot.class)
public abstract class SuspiciousTakeMixin {
	@Inject(method = "getRemainingItems", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$suspicious(CraftingInput input, Level level, CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
		if (!(level instanceof ServerLevel server)) return;
		NonNullList<ItemStack> left = SuspiciousBlocks.remaining(input, server);
		if (left != null) cir.setReturnValue(left);
	}
}
