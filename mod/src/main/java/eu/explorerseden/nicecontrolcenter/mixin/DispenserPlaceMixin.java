package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;

import eu.explorerseden.nicecontrolcenter.tweaks.DispenserHorn;
import eu.explorerseden.nicecontrolcenter.tweaks.DispenserPlace;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Dispensers place blocks: where a dispenser would just drop a block item (no behavior of its own, like shulker
 * boxes, TNT or carved pumpkins have), it places it instead. Droppers have their own method and keep dropping.
 * Since 26.3 most blocks are "swallowable" by sulfur cubes and get that behavior instead of the plain drop.
 * Also dispensers play goat horns (DispenserHorn).
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserPlaceMixin {
	@Shadow
	@Final
	private static DefaultDispenseItemBehavior DEFAULT_BEHAVIOR;

	@Inject(method = "getDispenseMethod", at = @At("RETURN"), cancellable = true)
	private void nicecontrolcenter$placeBlocks(Level level, ItemStack stack, CallbackInfoReturnable<DispenseItemBehavior> cir) {
		DispenseItemBehavior vanilla = cir.getReturnValue();
		// Goat horns are blown, not thrown out (they'd otherwise just drop).
		if (vanilla == DEFAULT_BEHAVIOR && DispenserHorn.plays(stack) && TweaksControl.settings().dispensers.goatHorns) {
			cir.setReturnValue(DispenserHorn.INSTANCE);
			return;
		}
		// Dropped as an item, or (26.3+) swallowable by a sulfur cube, which drops it when there's none in front.
		boolean drops = vanilla == DEFAULT_BEHAVIOR || vanilla.getClass().getSimpleName().equals("SulfurCubeBlockDispenseItemBehavior");
		if (!drops || !(stack.getItem() instanceof BlockItem)) return;
		if (!stack.isItemEnabled(level.enabledFeatures()) || !TweaksControl.settings().dispensers.placeBlocks) return;
		cir.setReturnValue(DispenserPlace.over(vanilla));
	}
}
