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
	/** Wear to scale at the end of the tick. Server thread. */
	private static final java.util.List<Runnable> later = new java.util.ArrayList<>();

	private final double multiplier;
	private final boolean preserve;
	private double owed;

	/** preserve: never let the tool break (it stops one use short). */
	ToolWear(double multiplier, boolean preserve) {
		this.multiplier = multiplier;
		this.preserve = preserve;
	}

	/**
	 * For the block the player broke themselves: the game wears the tool only after the break event, so
	 * this scales the wear at the end of the tick (damageBefore is the tool's damage now, in the event).
	 */
	static void afterThisBreak(double multiplier, boolean preserve, ServerPlayer player, ItemStack tool, int damageBefore) {
		if (multiplier == 1 || tool.isEmpty() || !tool.isDamageableItem()) return;
		later.add(() -> {
			if (player.getMainHandItem() == tool) new ToolWear(multiplier, preserve).apply(player, tool, damageBefore);
		});
	}

	static void tick() {
		if (later.isEmpty()) return;
		java.util.List<Runnable> now = new java.util.ArrayList<>(later);
		later.clear();
		now.forEach(Runnable::run);
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
