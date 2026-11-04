package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;

/**
 * A spear's charge held by something without attack damage (an NPC's mannequin showing its fighter's charge) hits
 * nothing instead of crashing the server; the fighter behind it does the hit.
 */
@Mixin(KineticWeapon.class)
public abstract class KineticNoDamageMixin {
	@Inject(method = "damageEntities", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noAttackDamage(ItemStack stack, int ticks, LivingEntity user, EquipmentSlot slot, CallbackInfo ci) {
		if (!user.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) ci.cancel();
	}
}
