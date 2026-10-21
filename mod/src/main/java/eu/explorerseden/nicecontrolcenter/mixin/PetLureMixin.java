package eu.explorerseden.nicecontrolcenter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import eu.explorerseden.nicecontrolcenter.pets.PetPerks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The pets' Lure perk: the rod's lure time, plus the pet's levels. */
@Mixin(FishingRodItem.class)
public class PetLureMixin {
	@ModifyExpressionValue(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getFishingTimeReduction(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/Entity;)F"))
	private float nicecontrolcenter$petLure(float seconds, @Local(argsOnly = true) Player player) {
		return PetPerks.lure(player, seconds);
	}
}
