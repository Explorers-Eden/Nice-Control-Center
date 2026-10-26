package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/** Event rewards for advancements: a real one (shown in the advancement screen, so not a recipe) was just completed. */
@Mixin(PlayerAdvancements.class)
public abstract class AdvancementRewardMixin {
	@Shadow
	private ServerPlayer player;

	@Shadow
	public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);

	@Inject(method = "award", at = @At("RETURN"))
	private void nicecontrolcenter$reward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || holder.value().display().isEmpty() || !getOrStartProgress(holder).isDone()) return;
		RewardsControl.advancement(player, holder.id().toString());
	}
}
