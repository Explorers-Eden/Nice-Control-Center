package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Grindstone to books: an enchanted item and a plain book in the grindstone give an enchanted book with the
 * item's enchantments (curses stay on the item), for levels. The grindstone shows no price, so it's told on
 * the action bar; the result can only be taken with enough levels.
 */
public final class GrindstoneBooks {
	/** The price of what the player's grindstone shows now (absent: not a book result). */
	private static final Map<Player, Integer> costs = new WeakHashMap<>();

	private GrindstoneBooks() {
	}

	public static boolean enabled() {
		return TweaksControl.settings().grindstone.booksEnabled;
	}

	/** May this go into a grindstone slot (on top of what vanilla allows)? */
	public static boolean isBook(ItemStack stack) {
		return enabled() && stack.is(Items.BOOK);
	}

	/** Which slot holds the enchanted item (0 or 1), or -1 when the two aren't an item and a book. */
	private static int itemSlot(ItemStack a, ItemStack b) {
		if (a.is(Items.BOOK) && movable(b)) return 1;
		if (b.is(Items.BOOK) && movable(a)) return 0;
		return -1;
	}

	private static boolean movable(ItemStack s) {
		if (s.isEmpty() || s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK)) return false;
		return s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).keySet().stream().anyMatch(h -> !h.is(EnchantmentTags.CURSE));
	}

	/** The enchanted book for these two slots, or null to leave it to vanilla. */
	public static ItemStack result(Player player, ItemStack a, ItemStack b) {
		costs.remove(player);
		if (!enabled() || player == null) return null;
		int slot = itemSlot(a, b);
		if (slot < 0) return null;
		ItemStack item = slot == 0 ? a : b;
		ItemEnchantments.Mutable moved = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		int levels = 0;
		for (var e : item.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
			Holder<Enchantment> h = e.getKey();
			if (h.is(EnchantmentTags.CURSE)) continue;
			moved.set(h, e.getIntValue());
			levels += e.getIntValue();
		}
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		book.set(DataComponents.STORED_ENCHANTMENTS, moved.toImmutable());
		int cost = cost(levels);
		costs.put(player, cost);
		if (player instanceof ServerPlayer sp && !sp.hasInfiniteMaterials()) {
			boolean enough = sp.experienceLevel >= cost;
			sp.sendSystemMessage(Component.literal(enough ? "Costs " + cost + (cost == 1 ? " level" : " levels") : "You need " + cost + " levels")
					.withStyle(enough ? ChatFormatting.GREEN : ChatFormatting.RED), true);
		}
		return book;
	}

	static int cost(int enchantLevels) {
		Tweaks.Grindstone g = TweaksControl.settings().grindstone;
		int c = (int) Math.round(enchantLevels * g.levelsPerEnchantLevel);
		return Math.max(g.minLevels, Math.min(g.maxLevels, c));
	}

	/** null: not our result (vanilla decides); else whether the player can pay for it. */
	public static Boolean mayPickup(Player player) {
		Integer cost = costs.get(player);
		if (cost == null) return null;
		return player.hasInfiniteMaterials() || player.experienceLevel >= cost;
	}

	/** Taking the book: pay, strip the item, use up a book. False when it isn't our result. */
	public static boolean take(Player player, Container slots) {
		Integer cost = costs.remove(player);
		if (cost == null) return false;
		ItemStack a = slots.getItem(0);
		ItemStack b = slots.getItem(1);
		int slot = itemSlot(a, b);
		if (slot < 0) return false;
		if (!player.hasInfiniteMaterials()) player.giveExperienceLevels(-cost);
		ItemStack item = (slot == 0 ? a : b).copy();
		EnchantmentHelper.updateEnchantments(item, m -> m.removeIf(h -> !h.is(EnchantmentTags.CURSE)));
		if (!TweaksControl.settings().grindstone.keepRepairCost) item.set(DataComponents.REPAIR_COST, 0);
		ItemStack book = (slot == 0 ? b : a).copy();
		book.shrink(1);
		slots.setItem(slot, item);
		slots.setItem(1 - slot, book);
		player.level().levelEvent(1042, player.blockPosition(), 0);
		return true;
	}
}
