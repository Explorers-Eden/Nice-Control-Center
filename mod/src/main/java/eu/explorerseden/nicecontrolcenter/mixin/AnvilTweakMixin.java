package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Anvil tweaks: never "Too Expensive!" and cheap renaming. Vanilla only asks hasInfiniteMaterials() a
 * second time when the cost reached 40; saying yes there keeps the result instead of emptying it.
 * The full cost is still charged, but vanilla clients show "Too Expensive!" for anything from 40 up,
 * so they are sent 39 and told the real price on the action bar; taking the result checks and
 * charges the real cost.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilTweakMixin {
	@Unique
	private static final int SHOWN_MAX = 39;

	@Shadow
	@Final
	private DataSlot cost;
	@Shadow
	private boolean onlyRenaming;

	@Unique
	private int nicecontrolcenter$realCost = -1;
	@Unique
	private Player nicecontrolcenter$player;

	@WrapOperation(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z", ordinal = 1))
	private boolean nicecontrolcenter$notTooExpensive(Player player, Operation<Boolean> original) {
		if (!player.level().isClientSide() && TweaksControl.settings().anvil.noTooExpensive) {
			nicecontrolcenter$player = player;
			return true;
		}
		return original.call(player);
	}

	@Inject(method = "createResult", at = @At("HEAD"))
	private void nicecontrolcenter$reset(CallbackInfo ci) {
		nicecontrolcenter$realCost = -1;
		nicecontrolcenter$player = null;
	}

	@Inject(method = "createResult", at = @At("TAIL"))
	private void nicecontrolcenter$cost(CallbackInfo ci) {
		Tweaks.Anvil a = TweaksControl.settings().anvil;
		int c = cost.get();
		if (c <= 0) return;
		if (a.cheapRename && onlyRenaming && c > a.renameCost) c = a.renameCost;
		int shown = c;
		if (a.noTooExpensive && c > SHOWN_MAX) {
			shown = SHOWN_MAX;
			nicecontrolcenter$realCost = c;
			if (nicecontrolcenter$player instanceof ServerPlayer sp)
				sp.sendSystemMessage(Component.literal("This costs " + c + " levels").withStyle(ChatFormatting.GREEN), true);
		}
		if (shown != cost.get()) cost.set(shown);
	}

	@WrapOperation(method = { "mayPickup", "onTake" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/DataSlot;get()I"))
	private int nicecontrolcenter$realCost(DataSlot slot, Operation<Integer> original) {
		int v = original.call(slot);
		return slot == cost && nicecontrolcenter$realCost > v ? nicecontrolcenter$realCost : v;
	}
}
