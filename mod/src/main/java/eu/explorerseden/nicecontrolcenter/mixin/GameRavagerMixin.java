package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ravager;

/**
 * Ravagers of the games (wave and dungeon mobs, Theron's last form) don't break the leaves they run into: the arena
 * stays as built, and nothing hanging on those leaves (a lever) pops off as an item.
 */
@Mixin(Ravager.class)
public abstract class GameRavagerMixin {
	@WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
	private boolean nicecontrolcenter$keepArena(ServerLevel level, BlockPos pos, boolean drop, Entity breaker, Operation<Boolean> original) {
		if (((Entity) (Object) this).entityTags().stream().anyMatch(t -> t.startsWith("ncc_") || t.equals("mob_manager.settings.exclude"))) return false;
		return original.call(level, pos, drop, breaker);
	}
}
