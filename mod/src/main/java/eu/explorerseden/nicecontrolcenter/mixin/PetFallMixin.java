package eu.explorerseden.nicecontrolcenter.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import eu.explorerseden.nicecontrolcenter.pets.PetPerks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The pets' Soft Paws perk takes part of a fall's damage. */
@Mixin(LivingEntity.class)
public class PetFallMixin {
	@ModifyReturnValue(method = "calculateFallDamage", at = @At("RETURN"))
	private int nicecontrolcenter$softPaws(int damage) {
		return (Object) this instanceof Player player ? PetPerks.softFall(player, damage) : damage;
	}
}
