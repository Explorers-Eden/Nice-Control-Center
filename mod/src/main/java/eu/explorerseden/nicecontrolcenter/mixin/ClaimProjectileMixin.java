package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/**
 * Arrows and other projectiles in claims: they don't set off blocks (targets, bells, TNT with flame
 * arrows, decorated pots…) or break item frames, paintings and vehicles for someone who couldn't do that
 * by hand. Animals and players are checked as damage (ClaimProtection.allowDamage).
 */
@Mixin(Projectile.class)
public abstract class ClaimProjectileMixin {
	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$entities(HitResult hit, CallbackInfo ci) {
		Projectile self = (Projectile) (Object) this;
		if (self.level().isClientSide() || !(hit instanceof EntityHitResult eh)) return;
		Entity target = eh.getEntity();
		if (target instanceof LivingEntity) return;
		if (!ClaimProtection.projectileAllowed(self, target.blockPosition(), Claims.Role.BUILD)) {
			ci.cancel();
			self.discard();
		}
	}

	@WrapOperation(method = "onHitBlock", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;onProjectileHit(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/entity/projectile/Projectile;)V"))
	private void nicecontrolcenter$blocks(BlockState state, Level level, BlockState again, BlockHitResult hit, Projectile projectile, Operation<Void> original) {
		if (!level.isClientSide() && !ClaimProtection.projectileAllowed(projectile, hit.getBlockPos(), Claims.Role.BUILD)) return;
		original.call(state, level, again, hit, projectile);
	}
}
