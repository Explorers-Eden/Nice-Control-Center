package eu.explorerseden.nicecontrolcenter.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Anvil tweaks: never "Too Expensive!" (costs over the limit are lowered to it, so vanilla clients see
 * a normal price) and cheap renaming. Vanilla only asks hasInfiniteMaterials() a second time when the
 * cost reached 40; saying yes there keeps the result instead of emptying it.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilTweakMixin {
	@Shadow
	@Final
	private DataSlot cost;
	@Shadow
	private boolean onlyRenaming;

	@WrapOperation(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z", ordinal = 1))
	private boolean nicecontrolcenter$notTooExpensive(Player player, Operation<Boolean> original) {
		if (!player.level().isClientSide() && TweaksControl.settings().anvil.noTooExpensive) return true;
		return original.call(player);
	}

	@Inject(method = "createResult", at = @At("TAIL"))
	private void nicecontrolcenter$cost(CallbackInfo ci) {
		Tweaks.Anvil a = TweaksControl.settings().anvil;
		int c = cost.get();
		if (c <= 0) return;
		if (a.cheapRename && onlyRenaming && c > a.renameCost) c = a.renameCost;
		if (a.noTooExpensive && c > a.maxCost) c = a.maxCost;
		if (c != cost.get()) cost.set(c);
	}
}
