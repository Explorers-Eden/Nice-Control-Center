package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;

import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/** Explosions don't hurt animals, villagers, item frames or armor stands in claims that don't allow explosions. */
@Mixin(ServerExplosion.class)
public abstract class ClaimExplosionEntityMixin {
	@WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
	private boolean nicecontrolcenter$claims(Entity entity, ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
		if (!ClaimProtection.explosionMayHurt(entity)) return false;
		return original.call(entity, level, source, amount);
	}
}
