package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import eu.explorerseden.nicecontrolcenter.claims.ClaimMode;

/** The claim tool doesn't go into bundles (where nothing could take it away again). */
@Mixin(BundleContents.Mutable.class)
public abstract class ClaimToolBundleMixin {
	@Inject(method = "tryInsert", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noTool(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		if (ClaimMode.isTool(stack)) cir.setReturnValue(0);
	}
}
