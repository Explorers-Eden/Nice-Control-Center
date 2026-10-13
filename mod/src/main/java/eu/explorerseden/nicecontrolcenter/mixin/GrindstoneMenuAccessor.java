package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.GrindstoneMenu;

@Mixin(GrindstoneMenu.class)
public interface GrindstoneMenuAccessor {
	@Accessor("repairSlots")
	Container nicecontrolcenter$repairSlots();
}
