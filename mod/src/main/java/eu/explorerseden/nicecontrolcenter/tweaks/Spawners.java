package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


/**
 * Silk touch spawners: a spawner mined with one of the configured tools (with Silk Touch, unless that's
 * switched off) drops as an item that keeps its mob and settings,
 * and places like that again for normal players (marked items only; other spawner data stays op-only).
 * The tool wears by the configured multiplier.
 */
public final class Spawners {
	public static final String MARK = "nicecontrolcenter:spawner";

	private Spawners() {
	}

	static void register() {
		PlayerBlockBreakEvents.AFTER.register(Spawners::after);
	}

	/** For the placement mixin: one of ours, so its spawner data may be applied for non-ops. */
	public static boolean marked(ItemStack stack) {
		return stack.is(Items.SPAWNER) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(MARK, false);
	}

	private static void after(Level lvl, Player p, BlockPos pos, BlockState state, BlockEntity be) {
		if (!state.is(Blocks.SPAWNER) || be == null || !(p instanceof ServerPlayer player) || !(lvl instanceof ServerLevel level)) return;
		Tweaks.Spawners s = TweaksControl.settings().spawners;
		if (!s.silkTouch || player.isCreative()) return;
		ItemStack tool = player.getMainHandItem();
		if (!TweaksControl.spawnerTool(tool)) return;
		var silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
		if (s.requireSilkTouch && EnchantmentHelper.getItemEnchantmentLevel(silk, tool) <= 0) return;
		CompoundTag data = be.saveWithoutMetadata(level.registryAccess());
		String mob = data.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "");
		if (!mob.isEmpty() && s.deniedMobs.contains(mob)) return;
		ItemStack drop = new ItemStack(Items.SPAWNER);
		drop.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(be.getType(), data));
		CompoundTag mark = new CompoundTag();
		mark.putBoolean(MARK, true);
		drop.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
		if (!mob.isEmpty()) {
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse(mob));
			if (type != null) drop.set(DataComponents.ITEM_NAME, Component.empty().append(type.getDescription()).append(" Spawner"));
		}
		Block.popResource(level, pos, drop);
		if (s.dropXp) ExperienceOrb.award(level, Vec3.atCenterOf(pos), 15 + level.getRandom().nextInt(29));
		// The game wears the tool only after this event, so the scaling waits until it has.
		ToolWear.afterThisBreak(s.durability, false, player, tool, tool.getDamageValue());
	}
}
