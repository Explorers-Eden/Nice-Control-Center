package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.Level;

import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/** Chorus fruit (and anything else that teleports randomly when eaten) doesn't in game areas and dungeons. */
@Mixin(TeleportRandomlyConsumeEffect.class)
public abstract class GameChorusMixin {
	@Inject(method = "apply", at = @At("HEAD"), cancellable = true)
	private void nicecontrolcenter$noChorus(Level level, ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (level.isClientSide() || !GameClaims.blocksChorus(entity)) return;
		if (entity instanceof ServerPlayer p) p.sendOverlayMessage(Component.literal("Chorus fruit doesn't teleport here").withStyle(ChatFormatting.RED));
		cir.setReturnValue(false);
	}
}
