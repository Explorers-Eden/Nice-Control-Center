package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.claims.ClaimProtection;

/**
 * Every block a player places needs building rights where it lands. The click checks cover most of it, but using a
 * block item on a block that does something itself (cave vines, a flower pot, a composter) can still place it next
 * to that block, e.g. glow berries. Adventure mode is the game's own business (can_place_on).
 */
@Mixin(BlockItem.class)
public abstract class ClaimPlaceMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$claimPlace(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(ctx.getPlayer() instanceof ServerPlayer p) || p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) return;
		if (!ClaimProtection.allowed(p, ctx.getLevel(), ctx.getClickedPos(), Claims.Role.BUILD)) {
			p.inventoryMenu.sendAllDataToRemote();
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
