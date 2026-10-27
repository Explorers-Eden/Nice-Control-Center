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

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$markPickup(Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer p)) return;
		ItemEntity self = (ItemEntity) (Object) this;
		// Game items outside their area can't be picked up: they vanish.
		if (GameClaims.strayGameItem(self)) {
			self.discard();
			ci.cancel();
			return;
		}
		GameClaims.pickedUp(p, getItem());
	}

	/** Game items thrown or carried (hoppers, water) out of their area vanish within a second. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void nicecontrolcenter$strayCheck(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!self.isRemoved() && self.getAge() % 20 == 10 && self.level() instanceof net.minecraft.server.level.ServerLevel && GameClaims.strayGameItem(self)) self.discard();
	}
}
