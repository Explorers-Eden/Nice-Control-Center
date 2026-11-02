package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;

import eu.explorerseden.nicecontrolcenter.games.RandomLooks;

/** No trading screen in dimensions with random looks (games, dungeons): the villagers there are scenery. */
@Mixin({ Villager.class, WanderingTrader.class })
public abstract class VillagerTradeMixin {
	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noTrade(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (!player.level().isClientSide() && RandomLooks.noTrading((Entity) (Object) this)) cir.setReturnValue(InteractionResult.PASS);
	}
}
