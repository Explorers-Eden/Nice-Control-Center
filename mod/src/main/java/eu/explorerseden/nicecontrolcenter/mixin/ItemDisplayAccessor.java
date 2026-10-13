package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

/** For pets (a floating player head). */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
	@Invoker("setItemStack")
	void nicecontrolcenter$setItemStack(ItemStack stack);
}
