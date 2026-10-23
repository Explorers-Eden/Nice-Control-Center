package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.ReplaceBlock;
import net.minecraft.world.item.enchantment.effects.ReplaceDisk;
import net.minecraft.world.item.enchantment.effects.SetBlockProperties;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/**
 * Enchantments that change blocks (Frost Walker's ice, data pack walkers' magma, grass or air, plows,
 * set_block_properties): in a claim the wearer needs to be a builder, a mob the claim's mob switch.
 * The blocks themselves are checked in ClaimActorEditMixin.
 */
@Mixin({ ReplaceDisk.class, ReplaceBlock.class, SetBlockProperties.class })
public abstract class ClaimEnchantEffectMixin {
	@WrapMethod(method = "apply")
	private void nicecontrolcenter$claims(ServerLevel level, int enchantmentLevel, EnchantedItemInUse item, Entity entity, Vec3 pos, Operation<Void> original) {
		ClaimProtection.beginEffect(entity);
		try {
			original.call(level, enchantmentLevel, item, entity, pos);
		} finally {
			ClaimProtection.end();
		}
	}
}
