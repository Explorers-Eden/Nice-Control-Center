package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * A mob pushes at most so many others per tick (Paper's max-entity-collisions); players are always pushed.
 * The search for others stops early too, but finds enough for cramming damage to work as in vanilla.
 */
@Mixin(LivingEntity.class)
public abstract class CollisionCapMixin {
	@Unique
	private static int nicecontrolcenter$pushes;

	@WrapMethod(method = "pushEntities")
	private void nicecontrolcenter$count(Operation<Void> original) {
		nicecontrolcenter$pushes = 0;
		original.call();
	}

	@WrapOperation(method = "pushEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPushableEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
	private List<Entity> nicecontrolcenter$search(Level level, Entity self, AABB box, Operation<List<Entity>> original) {
		int max = PerfControl.collisionMax();
		if (max < 0 || self instanceof Player || !(level instanceof ServerLevel server)) return original.call(level, self, box);
		int cramming = server.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
		int limit = Math.max(max, cramming > 0 ? cramming : 0) + 1;
		List<Entity> found = new ArrayList<>();
		var pushable = EntitySelector.pushableBy(self);
		level.getEntities(EntityTypeTest.forClass(Entity.class), box, e -> e != self && pushable.test(e), found, limit);
		return found;
	}

	@WrapOperation(method = "pushEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"))
	private void nicecontrolcenter$cap(LivingEntity self, Entity other, Operation<Void> original) {
		int max = PerfControl.collisionMax();
		if (max < 0 || self instanceof Player || other instanceof Player || ++nicecontrolcenter$pushes <= max) original.call(self, other);
	}
}
