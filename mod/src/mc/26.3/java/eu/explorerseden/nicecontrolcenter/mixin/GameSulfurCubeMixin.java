package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.monster.cubemob.SulfurCube;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/** Games: a sulfur cube made to fight (peaceful mobs fight) hurts on touch, like a slime. */
@Mixin(SulfurCube.class)
public abstract class GameSulfurCubeMixin {
	@ModifyReturnValue(method = "canDealDamage", at = @At("RETURN"))
	private boolean nicecontrolcenter$fights(boolean deals) {
		SulfurCube self = (SulfurCube) (Object) this;
		return deals || self.isEffectiveAi() && Aggro.made(self);
	}
}
