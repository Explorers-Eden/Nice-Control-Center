package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/** Games: tiny slimes bite too (vanilla lets only bigger ones hurt players). */
@Mixin(net.minecraft.world.entity.monster.cubemob.AbstractCubeMob.class)
public abstract class GameTinySlimeMixin {
	@ModifyReturnValue(method = "isDealsDamage", at = @At("RETURN"))
	private boolean nicecontrolcenter$tinyBite(boolean deals) {
		if (deals) return true;
		net.minecraft.world.entity.monster.cubemob.AbstractCubeMob self = (net.minecraft.world.entity.monster.cubemob.AbstractCubeMob) (Object) this;
		return self.isTiny() && self.isEffectiveAi() && self instanceof net.minecraft.world.entity.monster.Enemy && Aggro.gameMob(self);
	}
}
