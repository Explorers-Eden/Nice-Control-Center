package eu.explorerseden.nicecontrolcenter.tweaks;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Suspicious sand and gravel crafting: sand or gravel plus any one other item in a crafting grid gives suspicious
 * sand or gravel holding one of that item, components and all, to be brushed out again. Suspicious sand and gravel
 * can't go inside. Vanilla recipes win when one matches; nothing comes back from the grid (no empty buckets).
 */
public final class SuspiciousBlocks {
	private SuspiciousBlocks() {
	}

	public static boolean enabled() {
		return TweaksControl.settings().crafting.suspiciousBlocks;
	}

	/** The sand or gravel and the item to hide in it, or null when the grid isn't sand/gravel plus one other item. */
	private static ItemStack[] parts(CraftingInput input) {
		if (!enabled() || input.ingredientCount() != 2) return null;
		ItemStack base = null, content = null;
		for (ItemStack s : input.items()) {
			if (s.isEmpty()) continue;
			if (base == null && (s.is(Items.SAND) || s.is(Items.GRAVEL))) base = s;
			else content = s;
		}
		if (base == null || content == null || content.is(Items.SUSPICIOUS_SAND) || content.is(Items.SUSPICIOUS_GRAVEL)) return null;
		return new ItemStack[] { base, content };
	}

	/** The suspicious block for this grid (called only when no recipe matched), or null. */
	public static ItemStack result(CraftingInput input, Level level) {
		ItemStack[] parts = parts(input);
		if (parts == null) return null;
		BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("brushable_block"));
		if (type == null) return null;
		ItemStack content = parts[1];
		CompoundTag tag = new CompoundTag();
		tag.put("item", ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), content.copyWithCount(1)).getOrThrow());
		ItemStack out = new ItemStack(parts[0].is(Items.SAND) ? Items.SUSPICIOUS_SAND : Items.SUSPICIOUS_GRAVEL);
		out.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(type, tag));
		out.set(DataComponents.ITEM_NAME, Component.empty().append(out.getHoverName()).append(" (").append(content.getHoverName()).append(")"));
		return out;
	}

	/**
	 * What stays in the grid after crafting one: nothing, so a bucket inside doesn't also hand back an empty one.
	 * Null leaves it to vanilla (not our grid, or a real recipe matches).
	 */
	public static NonNullList<ItemStack> remaining(CraftingInput input, ServerLevel level) {
		if (parts(input) == null || level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).isPresent()) return null;
		return NonNullList.withSize(input.size(), ItemStack.EMPTY);
	}
}
