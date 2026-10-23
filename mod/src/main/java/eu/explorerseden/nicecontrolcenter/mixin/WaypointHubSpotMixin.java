package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.hubs.Hubs;

/**
 * An armor stand is only placed where no entity is in the way, dropped items included. A broken hub drops its
 * item right where it stood, so replacing a hub on the same spot silently did nothing until that item was picked up
 * or gone. For waypoint hub items, dropped items and XP orbs don't count; anything else still blocks as in vanilla.
 */
@Mixin(ArmorStandItem.class)
public abstract class WaypointHubSpotMixin {
	@WrapOperation(method = "useOn", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
	private List<Entity> nicecontrolcenter$hubSpot(Level level, Entity except, AABB box, Operation<List<Entity>> original,
			@Local(argsOnly = true) UseOnContext context) {
		List<Entity> found = original.call(level, except, box);
		if (found.isEmpty() || !Hubs.isHubItem(context.getItemInHand())) return found;
		return found.stream().filter(e -> !(e instanceof ItemEntity) && !(e instanceof ExperienceOrb)).toList();
	}
}
