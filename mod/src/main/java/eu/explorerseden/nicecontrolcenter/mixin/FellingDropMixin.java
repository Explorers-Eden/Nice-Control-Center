package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import eu.explorerseden.nicecontrolcenter.tweaks.Felling;

/** Pets: who caused an item drop. Tree felling / vein mining with "drops where the first block broke": items and XP land there; a falling tree's items wait until it lands. */
@Mixin(ServerLevel.class)
public abstract class FellingDropMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$gather(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		// Pets' Fetch: items that dropped because of a player (first, a falling tree's drops are held back below).
		if (((ServerLevel) (Object) this).getServer().isSameThread()) eu.explorerseden.nicecontrolcenter.pets.PlayerDrops.spawned(entity);
		BlockPos at = Felling.dropPoint();
		if (at != null && (entity instanceof ItemEntity || entity instanceof ExperienceOrb)) entity.setPos(at.getX() + 0.5, at.getY() + 0.25, at.getZ() + 0.5);
		// A falling tree's drops wait until it lands.
		if (Felling.capture(entity)) cir.setReturnValue(true);
	}
}
