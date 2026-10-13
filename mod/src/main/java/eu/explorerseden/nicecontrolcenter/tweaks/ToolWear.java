package eu.explorerseden.nicecontrolcenter.tweaks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Tool wear times a multiplier (1 = normal, 0.5 = half, 0 = none, 5 = five times): after the game has
 * worn the tool for a block (Unbreaking included), the wear is scaled, with fractions carried over to
 * the next block. Used by tree felling, vein mining and spawner pickup.
 */
final class ToolWear {
	private final double multiplier;
	private final boolean preserve;
	private double owed;

	/** preserve: never let the tool break (it stops one use short). */
	ToolWear(double multiplier, boolean preserve) {
		this.multiplier = multiplier;
		this.preserve = preserve;
	}

	/** Call after a block was broken with tool, which had damageBefore before it. */
	void apply(ServerPlayer player, ItemStack tool, int damageBefore) {
		if (multiplier == 1 || tool.isEmpty() || !tool.isDamageableItem()) return;
		owed += (tool.getDamageValue() - damageBefore) * multiplier;
		int take = (int) owed;
		owed -= take;
		int next = damageBefore + take;
		int max = tool.getMaxDamage();
		if (next >= max - 1 && preserve) next = max - 1;
		if (next >= max) {
			tool.setDamageValue(max - 1);
			tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
		} else {
			tool.setDamageValue(next);
		}
	}
}
