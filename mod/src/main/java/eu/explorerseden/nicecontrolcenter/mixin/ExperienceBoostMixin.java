package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;

import com.llamalad7.mixinextras.sugar.Local;

import eu.explorerseden.nicecontrolcenter.players.Perks;

/** The XP boost reward: experience from orbs times the player's multiplier (not /xp or other sources). */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceBoostMixin {
	@ModifyArg(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;giveExperiencePoints(I)V"))
	private int nicecontrolcenter$boost(int amount, @Local(argsOnly = true) Player player) {
		double f = Perks.factor(player.getStringUUID(), Perks.Kind.XP);
		return f > 1 ? (int) Math.round(amount * f) : amount;
	}
}
