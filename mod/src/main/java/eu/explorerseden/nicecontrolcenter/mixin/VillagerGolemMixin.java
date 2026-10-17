package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Iron golems when villagers panic: vanilla wants the villager to have slept in the last day, and 5 gossiping or
 * 3 panicking villagers nearby who all want a golem. With the tweak on, only panic calls one (VillagerPanicTrigger,
 * every 5 seconds) and one villager is enough; "no golem around" (GOLEM_DETECTED_RECENTLY) still applies.
 */
@Mixin(Villager.class)
public abstract class VillagerGolemMixin {
	@Inject(method = "golemSpawnConditionsMet", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noSleepNeeded(long gameTime, CallbackInfoReturnable<Boolean> cir) {
		if (TweaksControl.settings().villagers.panicGolems) cir.setReturnValue(true);
	}

	@Redirect(method = "gossip", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/npc/villager/Villager;spawnGolemIfNeeded(Lnet/minecraft/server/level/ServerLevel;JI)V"))
	private void nicecontrolcenter$noGossipGolem(Villager self, ServerLevel level, long gameTime, int minVillagerAmount) {
		if (!TweaksControl.settings().villagers.panicGolems) self.spawnGolemIfNeeded(level, gameTime, minVillagerAmount);
	}

	@ModifyVariable(method = "spawnGolemIfNeeded", at = @At("HEAD"), argsOnly = true)
	private int nicecontrolcenter$oneVillagerEnough(int minVillagerAmount) {
		return TweaksControl.settings().villagers.panicGolems ? 1 : minVillagerAmount;
	}
}
