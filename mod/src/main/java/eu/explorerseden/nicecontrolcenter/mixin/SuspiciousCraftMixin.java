package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.tweaks.SuspiciousBlocks;

/** Suspicious sand and gravel crafting: fills the empty result of a grid no recipe matched (both the table and the 2×2 grid). */
@Mixin(CraftingMenu.class)
public abstract class SuspiciousCraftMixin {
	// The result is read three times (result slot, remote slot, packet); changing it at the first read reaches all of them.
	@ModifyVariable(method = "slotChangedCraftingGrid", at = @At(value = "LOAD", ordinal = 0), ordinal = 0)
	private static ItemStack nicecontrolcenter$suspicious(ItemStack result, AbstractContainerMenu menu, ServerLevel level, Player player,
			CraftingContainer grid, ResultContainer results) {
		if (!result.isEmpty()) return result;
		ItemStack ours = SuspiciousBlocks.result(grid.asCraftInput(), level);
		return ours != null ? ours : result;
	}
}
