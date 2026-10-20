package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import eu.explorerseden.nicecontrolcenter.pets.PlayerDrops;

/** Pets' Fetch: a mob's loot is its killer's; a player's own death drops are nobody's. */
@Mixin(LivingEntity.class)
public abstract class DropCauseDeathMixin {
	@Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
	private void nicecontrolcenter$start(ServerLevel level, DamageSource source, CallbackInfo ci) {
		if (!((Object) this instanceof Player) && source.getEntity() instanceof ServerPlayer killer) PlayerDrops.push(killer);
		else PlayerDrops.pushNobody();
	}

	@Inject(method = "dropAllDeathLoot", at = @At("RETURN"))
	private void nicecontrolcenter$end(ServerLevel level, DamageSource source, CallbackInfo ci) {
		PlayerDrops.pop();
	}
}
