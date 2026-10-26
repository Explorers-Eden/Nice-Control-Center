package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/** Items picked up inside a game claim get its mark (removed or sent to the inbox when the player leaves). */
@Mixin(ItemEntity.class)
public abstract class GameClaimPickupMixin {
	@Shadow
	public abstract ItemStack getItem();

	@Inject(method = "playerTouch", at = @At("HEAD"))
	private void nicecontrolcenter$markPickup(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer p) GameClaims.pickedUp(p, getItem());
	}
}
