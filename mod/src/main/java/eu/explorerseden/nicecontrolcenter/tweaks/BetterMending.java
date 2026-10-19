package eu.explorerseden.nicecontrolcenter.tweaks;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Better Mending: sneak + right click an anvil and the Mending item in the main hand (else the off hand) is
 * repaired with the player's experience points, at the rate Mending uses (normally 2 durability per point).
 */
public final class BetterMending {
	private BetterMending() {
	}

	static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel server) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (!TweaksControl.settings().mending.anvilRepair || !sp.isShiftKeyDown() || sp.isSpectator()) return InteractionResult.PASS;
			if (!level.getBlockState(hit.getBlockPos()).is(BlockTags.ANVIL)) return InteractionResult.PASS;
			// The off hand only with nothing in the main hand: a block in the main hand is still placed against the anvil.
			ItemStack stack = repairable(sp.getMainHandItem()) ? sp.getMainHandItem()
					: sp.getMainHandItem().isEmpty() && repairable(sp.getOffhandItem()) ? sp.getOffhandItem() : null;
			if (stack == null) return InteractionResult.PASS;
			int points = points(sp);
			if (points <= 0 && !sp.getAbilities().instabuild) {
				sp.sendOverlayMessage(Component.literal("You need experience to repair it.").withStyle(ChatFormatting.RED));
				return InteractionResult.FAIL;
			}
			// Durability one point of experience repairs (Mending's own rate, 2 in vanilla).
			int rate = Math.max(1, EnchantmentHelper.modifyDurabilityToRepairFromXp(server, stack, 1));
			int damage = stack.getDamageValue();
			int repair = sp.getAbilities().instabuild ? damage : Math.min(damage, points * rate);
			int cost = (repair + rate - 1) / rate;
			stack.setDamageValue(damage - repair);
			if (!sp.getAbilities().instabuild) sp.giveExperiencePoints(-cost);
			level.playSound(null, hit.getBlockPos(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.2f);
			level.playSound(null, sp.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 0.9f + level.getRandom().nextFloat() * 0.2f);
			sp.sendOverlayMessage(Component.literal(stack.isDamaged() ? "Repaired " + repair + " durability for " + cost + " experience; out of experience."
					: "Fully repaired for " + cost + " experience.").withStyle(ChatFormatting.GREEN));
			return InteractionResult.SUCCESS;
		});
	}

	private static boolean repairable(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamaged() && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP);
	}

	/** All the player's experience points: the levels plus the progress into the next one. */
	private static int points(ServerPlayer p) {
		long total = 0;
		for (int level = 0; level < p.experienceLevel; level++) total += needed(level);
		total += Math.round(p.experienceProgress * needed(p.experienceLevel));
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	/** Points from this level to the next (the game's formula). */
	private static int needed(int level) {
		return level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
	}
}
